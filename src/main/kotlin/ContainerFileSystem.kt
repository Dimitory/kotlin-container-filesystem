import allocator.AllocationMapPage
import allocator.AllocationMapPageCodec
import allocator.ContainerAllocator
import container.ContainerFile
import container.ContainerHeader
import container.ContainerHeaderCodec
import container.ContainerLayout
import streams.ContainerInputStream
import streams.ContainerOutputStream
import data.DataStorage
import metadata.EntityMetadata
import metadata.EntityMetadataBlock
import metadata.EntityMetadataBlockCodec
import metadata.EntityType
import metadata.EntryInfo
import metadata.MetadataStorage
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

class ContainerFileSystem private constructor(
    private val file: ContainerFile,
    private val allocator: ContainerAllocator,
    private val metadataStorage: MetadataStorage,
    private val dataStorage: DataStorage,
) : AutoCloseable {
    companion object {
        internal const val DEFAULT_BLOCK_SIZE: Int = 4096

        @Suppress("unused")
        fun open(path: Path, mode: ContainerOpenMode = ContainerOpenMode.OpenOrCreate): ContainerFileSystem {
            return when (mode) {
                ContainerOpenMode.OpenExisting -> openExisting(path)
                ContainerOpenMode.CreateNew -> createNew(path)
                ContainerOpenMode.OpenOrCreate ->
                    if (Files.exists(path)) {
                        openExisting(path)
                    } else {
                        createNew(path)
                    }
            }
        }

        private fun openExisting(path: Path): ContainerFileSystem {
            require(Files.exists(path)) {
                "Container does not exist: $path"
            }
            val randomAccessFile = RandomAccessFile(path.toFile(), "rw")
            val header = ContainerHeaderCodec.read(randomAccessFile)
            val containerFile = ContainerFile(randomAccessFile)
            val allocator = ContainerAllocator(containerFile, header)
            val metadataStorage = MetadataStorage(allocator, header)
            val dataStorage = DataStorage(allocator, header)
            return ContainerFileSystem(containerFile, allocator, metadataStorage, dataStorage)
        }

        private fun createNew(path: Path): ContainerFileSystem {
            require(!Files.exists(path)) {
                "Container already exists: $path"
            }
            val randomAccessFile = RandomAccessFile(path.toFile(), "rw")
            val header = ContainerHeader.createDefault(DEFAULT_BLOCK_SIZE)
            val containerFile = ContainerFile(randomAccessFile)
                .cleanAndInitialize(header)
            AllocationMapPageCodec.write(
                file = containerFile,
                blockIndex = ContainerLayout.FIRST_ALLOCATION_MAP_BLOCK,
                blockSize = header.blockSize,
                page = AllocationMapPage(
                    bitmap = ByteArray(AllocationMapPageCodec.bitmapSize(header.blockSize)),
                    nextBlock = ContainerLayout.NONE_BLOCK
                ))
            EntityMetadataBlockCodec.write(
                file = containerFile,
                blockIndex = ContainerLayout.FIRST_METADATA_BLOCK,
                blockSize = header.blockSize,
                metadataBlock = EntityMetadataBlock(
                    nextBlock = ContainerLayout.NONE_BLOCK,
                    entries = arrayOfNulls(EntityMetadataBlockCodec.slotsPerBlock(header.blockSize))
                )
            )
            containerFile.flush()
            val allocator = ContainerAllocator(containerFile, header)
            val metadataStorage = MetadataStorage(allocator, header)
            val dataStorage = DataStorage(allocator, header)
            return ContainerFileSystem(containerFile, allocator, metadataStorage, dataStorage)
        }
    }

    @Suppress("unused")
    fun openRead(path: Path): InputStream {
        val metadata = requireEntry(path)
        require(metadata.type == EntityType.File) {
            "Not a file: $path"
        }
        return ContainerInputStream(metadata, dataStorage)
    }

    @Suppress("unused")
    fun openWrite(path: Path, openMode: OpenMode = OpenMode.CreateNew): OutputStream {
        val parent = resolveOrCreateParent(path)
        val metadata = metadataStorage.findChild(parent.id, path.name)
        if (metadata != null) {
            require(metadata.type == EntityType.File) {
                "Not a file: $path"
            }
        }

        val openedMetadata = when (openMode) {
            OpenMode.CreateNew -> {
                check(metadata == null) {
                    "Entry already exists: $path"
                }
                metadataStorage.create(
                    parentId = parent.id,
                    name = path.name,
                    type = EntityType.File
                )
            }

            OpenMode.CreateOrTruncate -> {
                if (metadata == null) {
                    metadataStorage.create(
                        parentId = parent.id,
                        name = path.name,
                        type = EntityType.File
                    )
                } else {
                    require(metadata.type == EntityType.File) {
                        "Not a file: $path"
                    }

                    dataStorage.delete(metadata)

                    metadata.copy(
                        size = 0L,
                        firstExtentBlock = ContainerLayout.NONE_BLOCK
                    ).also {
                        metadataStorage.update(it)
                    }
                }
            }

            OpenMode.Append -> {
                val existing = requireNotNull(metadata) {
                    "File does not exist: $path"
                }
                require(existing.type == EntityType.File) {
                    "Not a file: $path"
                }
                existing
            }
        }

        return ContainerOutputStream(openedMetadata, dataStorage)
        {
            updated ->
                metadataStorage.update(updated)
                metadataStorage.flush()
        }
    }

    @Suppress("unused")
    fun delete(path: Path, recursive: Boolean = false) {
        val metadata = requireEntry(path)
        if (metadata.type == EntityType.Directory) {
            val children = metadataStorage
                .list(metadata.id)
                .toList()

            if (children.isNotEmpty() && !recursive) {
                error("Directory is not empty: $path")
            }

            if (recursive) {
                deleteRecursive(metadata)
            } else {
                metadataStorage.delete(metadata.id)
            }
        }
        else
        {
            dataStorage.delete(metadata)
            metadataStorage.delete(metadata.id)
        }
    }

    @Suppress("unused")
    fun move(source: Path, destination: Path) {
        val sourceMetadata = requireEntry(source)
        val destinationParent = resolveParent(destination)
        val destinationExists = metadataStorage.findChild(destinationParent.id, destination.name)
        check(destinationExists == null) {
            "Already exists: $destination"
        }

        if (sourceMetadata.type == EntityType.Directory) {
            if (isSubdirectory(destinationParent, sourceMetadata.id)) {
                error("Cannot move directory into itself")
            }
        }

        metadataStorage.update(
            sourceMetadata.copy(
                parentId = destinationParent.id,
                name = destination.name
            )
        )
    }

    @Suppress("unused")
    fun exists(path: Path): Boolean  {
        return resolve(path) != null
    }

    @Suppress("unused")
    fun list(path: Path): Sequence<EntryInfo> {
        val directory = requireEntry(path)
        require(directory.type == EntityType.Directory) {
            "Not a directory: $path"
        }

        return metadataStorage
            .list(directory.id)
            .map(::toEntryInfo)
    }

    @Suppress("unused")
    fun getInfo(path: Path): EntryInfo? {
        return resolve(path)?.let(::toEntryInfo)
    }

    @Suppress("unused")
    fun flush() {
        metadataStorage.flush()
        dataStorage.flush()
        allocator.flush()
        file.flush()
    }

    override fun close() {
        metadataStorage.close()
        dataStorage.close()
        allocator.close()
        file.close()
    }

    private fun isSubdirectory(entry: EntityMetadata, ancestorId: Long): Boolean {
        var current = entry
        while (current.id != metadataStorage.root.id) {
            if (current.id == ancestorId) {
                return true
            }
            current = metadataStorage.findById(current.parentId) ?: return false
        }
        return current.id == ancestorId
    }

    private fun deleteRecursive(metadata: EntityMetadata) {
        if (metadata.type == EntityType.Directory) {
            val children = metadataStorage.list(metadata.id).toList()
            for (child in children) {
                deleteRecursive(child)
            }
        } else {
            dataStorage.delete(metadata)
        }
        metadataStorage.delete(metadata.id)
    }

    private fun resolveOrCreateParent(path: Path): EntityMetadata {
        var current = metadataStorage.root
        val components = path.normalize().filter { it.toString().isNotEmpty() }
        for (component in components.dropLast(1)) {
            val name = component.toString()
            val next = metadataStorage.findChild(current.id, name)
                ?: metadataStorage.create(name = name, type = EntityType.Directory, parentId = current.id)
            require(next.type == EntityType.Directory) {
                "Not a directory: $component"
            }
            current = next
        }
        return current
    }

    private fun requireEntry(path: Path): EntityMetadata {
        return requireNotNull(resolve(path)) {
            "Entry does not exist: $path"
        }
    }

    private fun resolve(path: Path): EntityMetadata? {
        var current = metadataStorage.root
        val components = path.normalize().filter { it.toString().isNotEmpty() }
        for (component in components) {
            if (current.type != EntityType.Directory)
                return null
            current = metadataStorage.findChild(current.id, component.toString()) ?: return null
        }
        return current
    }

    private fun resolveParent(path: Path): EntityMetadata {
        var current: EntityMetadata = metadataStorage.root
        val components = path.normalize().filter { it.toString().isNotEmpty() }
        for (component in components.dropLast(1)) {
            val next = metadataStorage.findChild(current.id, component.toString())
            checkNotNull(next) {
                "Directory does not exist: $component"
            }
            require(next.type == EntityType.Directory) {
                "Not a directory: $component"
            }
            current = next
        }
        return current
    }

    private fun toEntryInfo(metadata: EntityMetadata): EntryInfo {
        return EntryInfo(
            name = metadata.name,
            type = metadata.type,
            size = metadata.size
        )
    }
}
