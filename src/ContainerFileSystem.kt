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

public class ContainerFileSystem private constructor(
    private val file: ContainerFile,
    private val allocator: ContainerAllocator,
    private val metadataStorage: MetadataStorage,
    private val dataStorage: DataStorage,
) : AutoCloseable {
    companion object {
        internal const val DEFAULT_BLOCK_SIZE: Int = 4096

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
            val dataStorage = DataStorage(allocator, header);
            return ContainerFileSystem(containerFile, allocator, metadataStorage, dataStorage);
        }

        private fun createNew(path: Path): ContainerFileSystem {
            require(!Files.exists(path)) {
                "Container already exists: $path"
            };
            val randomAccessFile = RandomAccessFile(path.toFile(), "rw")
            val header = ContainerHeader.createDefault(DEFAULT_BLOCK_SIZE)
            val containerFile = ContainerFile(randomAccessFile)
            // TODO("Initialize file")
            containerFile.grow(ContainerHeaderCodec.SERIALIZED_SIZE + ContainerLayout.SYSTEM_BLOCK_COUNT * header.blockSize)
            ContainerHeaderCodec.write(randomAccessFile, header)
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
            val allocator = ContainerAllocator(containerFile, header)
            val metadataStorage = MetadataStorage(allocator, header)
            val dataStorage = DataStorage(allocator, header);
            containerFile.flush();
            return ContainerFileSystem(containerFile, allocator, metadataStorage, dataStorage);
        }
    }

    fun openRead(path: Path): InputStream {
        val metadata = requireEntry(path)
        require(metadata.type == EntityType.File) {
            "Not a file: $path"
        }
        return ContainerInputStream(metadata, dataStorage)
    }


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

    fun delete(path: Path) {
        TODO("Not yet implemented")
    }

    fun delete(path: Path, recursive: Boolean = false) {
        TODO("Not yet implemented")
    }

    fun move(source: Path, destination: String) {
        TODO("Not yet implemented")
    }

    fun rename(path: String, newName: String) {
        TODO("Not yet implemented")
    }

    fun exists(path: Path): Boolean  {
        TODO("Not yet implemented")
    }

    fun createDirectory(path: String) {
        TODO("Not yet implemented")
    }

    fun list(path: Path): Sequence<EntryInfo> {
        TODO("Not yet implemented")
    }

    fun getInfo(path: Path): EntryInfo? {
        TODO("Not yet implemented")
    }

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
        for (component in path) {
            if (current.type != EntityType.Directory)
                return null
            current = metadataStorage.findChild(current.id, component.toString()) ?: return null
        }
        return current
    }
}
