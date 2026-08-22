package metadata

import container.ContainerFile
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object EntityMetadataBlockCodec {
    const val HEADER_SIZE: Int =
        Long.SIZE_BYTES + // nextBlock
        Long.SIZE_BYTES   // usedSlots

    fun slotsPerBlock(blockSize: Int): Int {
        val count = (blockSize - HEADER_SIZE) / EntryMetadataCodec.SERIALIZED_SIZE
        require(count in 1..Long.SIZE_BITS)
        return count
    }

    private fun entryOffset(slotIndex: Int): Int {
        return HEADER_SIZE + slotIndex * EntryMetadataCodec.SERIALIZED_SIZE
    }

    fun write(file: ContainerFile, blockIndex: Long, blockSize: Int, metadataBlock: EntityMetadataBlock) {
        val buffer = ByteArray(blockSize)
        encode(metadataBlock, buffer)
        file.write(blockIndex * blockSize, buffer, 0, buffer.size)
    }

    fun encode(value: EntityMetadataBlock, destination: ByteArray) {
        val slotsPerBlock = slotsPerBlock(destination.size)
        require(value.entries.size == slotsPerBlock)
        destination.fill(0)
        ByteBuffer
            .wrap(destination)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(value.nextBlock)

        for (slotIndex in value.entries.indices) {
            val entry = value.entries[slotIndex] ?: continue
            EntryMetadataCodec.encode(
                value = entry,
                destination = destination,
                offset = entryOffset(slotIndex)
            )
        }
    }

    fun decode(source: ByteArray): EntityMetadataBlock {
        val slotsPerBlock = slotsPerBlock(source.size)
        val buffer = ByteBuffer
                .wrap(source)
                .order(ByteOrder.LITTLE_ENDIAN)
        val nextBlock = buffer.long
        val entries = arrayOfNulls<EntryMetadata>(slotsPerBlock)
        for (slotIndex in 0..<slotsPerBlock) {
            entries[slotIndex] = EntryMetadataCodec.decode(source, entryOffset(slotIndex))
        }
        return EntityMetadataBlock(
            nextBlock = nextBlock,
            entries = entries
        )
    }
}