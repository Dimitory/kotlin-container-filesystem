package data

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object DataExtentCodec {
    const val SERIALIZED_SIZE = Long.SIZE_BYTES+ Long.SIZE_BYTES

    fun encode(extent: DataExtent): ByteArray {
        return ByteBuffer
            .allocate(SERIALIZED_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(extent.startBlock)
            .putLong(extent.nextBlock)
            .array()
    }

    fun decode(bytes: ByteArray): DataExtent {
        require(bytes.size >= SERIALIZED_SIZE)
        val buffer = ByteBuffer
            .wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
        return DataExtent(
            startBlock = buffer.long,
            nextBlock = buffer.long,
        )
    }
}