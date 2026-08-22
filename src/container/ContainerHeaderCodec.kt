package container

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object ContainerHeaderCodec {

    const val SERIALIZED_SIZE: Int = Int.SIZE_BYTES + Short.SIZE_BYTES + Int.SIZE_BYTES

    fun read(file: RandomAccessFile): ContainerHeader {
        val buffer = ByteArray(SERIALIZED_SIZE)
        file.seek(0)
        file.readFully(buffer, 0, buffer.size)
        return decode(buffer)
    }

    fun write(file: RandomAccessFile, header: ContainerHeader) {
        val buffer = ByteArray(SERIALIZED_SIZE)
        encode(header, buffer)
        file.seek(0)
        file.write(buffer, 0, buffer.size)
    }

    fun encode(header: ContainerHeader, destination: ByteArray) {
        require(destination.size >= SERIALIZED_SIZE)
        ByteBuffer
            .wrap(destination, 0, SERIALIZED_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(header.magic)
            .putShort(header.version)
            .putInt(header.blockSize)
    }

    fun decode(source: ByteArray): ContainerHeader {
        require(source.size >= SERIALIZED_SIZE)
        val buffer = ByteBuffer
            .wrap(source, 0, SERIALIZED_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)

        val header = ContainerHeader(
            magic = buffer.int,
            version = buffer.short,
            blockSize = buffer.int
        )

        require(header.magic == ContainerHeader.MAGIC) {
            "Invalid container magic: ${header.magic}"
        }

        require(header.version == ContainerHeader.VERSION) {
            "Unsupported container version: ${header.version}"
        }

        require(header.blockSize > 0) {
            "Invalid block size: ${header.blockSize}"
        }

        return header
    }
}