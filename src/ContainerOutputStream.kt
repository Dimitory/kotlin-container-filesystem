import data.DataStorage
import metadata.EntryMetadata
import metadata.MetadataStorage
import java.io.OutputStream

class ContainerOutputStream internal constructor(
    private val metadataStorage: ContainerFileSystem,
    private val dataStorage: DataStorage,
    private val metadata: EntryMetadata
) : OutputStream() {
    override fun write(value: Int) {
        val buffer = byteArrayOf(value.toByte())
        write(buffer, 0, 1)
    }

    override fun write(buffer: ByteArray, bufferOffset: Int, length: Int) {
        dataStorage.append(
            metadata = metadata,
            buffer = buffer,
            bufferOffset = bufferOffset,
            length = length
        )
    }

    override fun flush() {
        dataStorage.flush(metadata)
        TODO("Not yet implemented")
    }

    override fun close() {
        TODO("Not yet implemented")
    }
}