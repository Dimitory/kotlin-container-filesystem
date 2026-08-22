package allocator

import container.ContainerFile
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object AllocationMapPageCodec {
    const val MAP_HEADER_SIZE = Long.SIZE_BYTES // nextBlock

    fun bitmapSize(blockSize: Int): Int {
        require(blockSize > MAP_HEADER_SIZE)
        return blockSize - MAP_HEADER_SIZE
    }

    fun blocksPerMap(blockSize: Int): Long {
        return bitmapSize(blockSize).toLong() * Byte.SIZE_BITS
    }

    fun write(file: ContainerFile, blockIndex: Long, blockSize: Int, page: AllocationMapPage) {
        val buffer = ByteArray(blockSize)
        encode(page, buffer)
        file.write(blockIndex * blockSize, buffer, 0, buffer.size)
    }

    fun encode(page: AllocationMapPage, destination: ByteArray) {
        ByteBuffer
            .wrap(destination, 0, destination.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(page.nextBlock)
            .put(page.bitmap)
    }

    fun decode(source: ByteArray): AllocationMapPage {
        val buffer = ByteBuffer
            .wrap(source, 0, source.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        val nextBlock = buffer.long
        val bitmap = ByteArray(source.size - MAP_HEADER_SIZE);
        buffer.get(bitmap)
        return AllocationMapPage(
            bitmap = bitmap,
            nextBlock = nextBlock
        )
    }
}