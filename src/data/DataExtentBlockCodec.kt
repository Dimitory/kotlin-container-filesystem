package data

import container.ContainerLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object DataExtentBlockCodec {
    private const val HEADER_SIZE = Long.SIZE_BYTES
    private const val SERIALIZED_SIZE = Long.SIZE_BYTES + Int.SIZE_BYTES

    fun slotsPerBlock(blockSize: Int): Int {
        val result = (blockSize - HEADER_SIZE) / SERIALIZED_SIZE
        return result
    }

    fun encode(value: DataExtentBlock, destination: ByteArray) {
        destination.fill(0)
        val buffer = ByteBuffer.wrap(destination).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(value.nextBlock)
        for (extent in value.extents) {
            if (extent == null) {
                buffer.putLong(ContainerLayout.NONE_BLOCK)
                buffer.putInt(0)
            } else {
                buffer.putLong(extent.startBlock)
                buffer.putInt(extent.blockCount)
            }
        }
    }

    fun decode(source: ByteArray): DataExtentBlock {
        val buffer = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
        val nextBlock = buffer.long
        val extents = arrayOfNulls<DataExtent>(slotsPerBlock(source.size))
        for (index in extents.indices) {
            val startBlock = buffer.long
            val blockCount = buffer.int
            if (startBlock != ContainerLayout.NONE_BLOCK) {
                extents[index] = DataExtent(startBlock, blockCount)
            }
        }
        return DataExtentBlock(nextBlock, extents)
    }
}
