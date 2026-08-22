package container

import java.io.RandomAccessFile

internal class ContainerFile internal constructor(
    private val file: RandomAccessFile,
) : AutoCloseable {

    val size: Long get() = file.length() - ContainerHeaderCodec.SERIALIZED_SIZE

    fun read(offset: Long, buffer: ByteArray, bufferOffset: Int = 0, length: Int = buffer.size - bufferOffset) {
        require(offset >= 0)
        require(bufferOffset >= 0)
        require(length >= 0)
        require(bufferOffset + length <= buffer.size)
        file.seek(ContainerHeaderCodec.SERIALIZED_SIZE + offset)
        file.readFully(buffer, bufferOffset, length)
    }

    fun write(offset: Long, buffer: ByteArray, bufferOffset: Int = 0, length: Int = buffer.size - bufferOffset) {
        require(offset >= 0)
        require(bufferOffset >= 0)
        require(length >= 0)
        require(bufferOffset + length <= buffer.size)
        file.seek(ContainerHeaderCodec.SERIALIZED_SIZE + offset)
        file.write(buffer, bufferOffset, length)
    }

    fun grow(requestedSize: Int) {
        require(requestedSize >= 0)
        file.setLength(file.length() + requestedSize)
    }

    fun flush() = file.fd.sync()

    override fun close() {
        flush();
        file.close()
    }
}
