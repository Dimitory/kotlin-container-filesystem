import allocator.AllocationMapPage
import allocator.AllocationMapPageCodec
import allocator.ContainerAllocator
import container.ContainerFile
import container.ContainerHeader
import container.ContainerHeaderCodec
import container.ContainerLayout
import container.OpenMode
import data.DataStorage
import metadata.EntityMetadataBlock
import metadata.EntityMetadataBlockCodec
import metadata.MetadataStorage
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

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
            require(Files.exists(path))
            val randomAccessFile = RandomAccessFile(path.toFile(), "rw")
            val header = ContainerHeaderCodec.read(randomAccessFile)
            val containerFile = ContainerFile(randomAccessFile)
            val allocator = ContainerAllocator(containerFile, header)
            val metadataStorage = MetadataStorage(allocator, header)
            val dataStorage = DataStorage(allocator, header);
            return ContainerFileSystem(containerFile, allocator, metadataStorage, dataStorage);
        }

        private fun createNew(path: Path): ContainerFileSystem {
            require(!Files.exists(path))
            val randomAccessFile = RandomAccessFile(path.toFile(), "rw")
            val header = ContainerHeader.createDefault(DEFAULT_BLOCK_SIZE)
            val containerFile = ContainerFile(randomAccessFile)
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

    fun open(path: String, mode: ContainerOpenMode = ContainerOpenMode.OpenExisting): ContainerFileSystem {
        TODO("Not yet implemented")
    }

    fun createDirectory(path: String) {
        TODO("Not yet implemented")
    }

    fun openRead(path: String): ContainerInputStream {
        TODO("Not yet implemented")
    }

    fun openWrite(path: String, openMode: OpenMode = OpenMode.CreateNew): ContainerOutputStream {
        TODO("Not yet implemented")
    }

    fun delete(path: String) {
        TODO("Not yet implemented")
    }

    fun delete(path: String, recursive: Boolean = false) {
        TODO("Not yet implemented")
    }

    fun move(source: String, destination: String) {
        TODO("Not yet implemented")
    }

    fun rename(path: String, newName: String) {
        TODO("Not yet implemented")
    }

    fun exists(path: String): Boolean  {
        TODO("Not yet implemented")
    }

    fun list(path: String): Sequence<EntryInfo> {
        TODO("Not yet implemented")
    }

    fun getInfo(path: String): EntryInfo? {
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
}
