package data

import allocator.BlockRange
import allocator.ContainerAllocator
import container.ContainerHeader
import container.ContainerLayout
import metadata.EntityMetadata

internal class DataStorage(
    private val allocator: ContainerAllocator,
    header: ContainerHeader,
) : AutoCloseable {
    val blockSize = header.blockSize

    fun delete(metadata: EntityMetadata) {
        var blockIndex = metadata.firstExtentBlock

        while (blockIndex != ContainerLayout.NONE_BLOCK) {
            val block = readExtentBlock(blockIndex)

            for (extent in block.extents) {
                extent ?: continue
                allocator.free(BlockRange(extent.startBlock, extent.blockCount))
            }

            val nextBlock = block.nextBlock
            allocator.free(BlockRange(blockIndex, 1))
            blockIndex = nextBlock
        }
    }

    fun flush() = allocator.flush()

    override fun close() = flush()

    fun capacity(extent: DataExtent): Long =
        extent.blockCount.toLong() * blockSize

    fun blocksForBytes(byteCount: Long): Int =
        ((byteCount + blockSize - 1) / blockSize).toInt()

    fun allocate(blockCount: Int, preferredAfterBlock: Long): BlockRange =
        allocator.allocate(blockCount, preferredAfterBlock)

    fun readData(
        extent: DataExtent,
        offset: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ) {
        allocator.read(extent.startBlock, offset, destination, destinationOffset, length)
    }

    fun writeData(
        extent: DataExtent,
        offset: Long,
        source: ByteArray,
        sourceOffset: Int,
        length: Int,
    ) {
        allocator.write(extent.startBlock, offset, source, sourceOffset, length)
    }

    fun readExtentBlock(blockIndex: Long): DataExtentBlock {
        val buffer = ByteArray(blockSize)
        allocator.read(blockIndex, 0, buffer)
        return DataExtentBlockCodec.decode(buffer)
    }

    fun writeExtentBlock(blockIndex: Long, block: DataExtentBlock) {
        val buffer = ByteArray(blockSize)
        DataExtentBlockCodec.encode(block, buffer)
        allocator.write(blockIndex, 0, buffer)
    }

    fun appendExtent(metadata: EntityMetadata, extent: DataExtent): EntityMetadata {
        if (metadata.firstExtentBlock == ContainerLayout.NONE_BLOCK) {
            return metadata.copy(firstExtentBlock = createExtentBlock(extent))
        }

        var blockIndex = metadata.firstExtentBlock

        while (true) {
            val block = readExtentBlock(blockIndex)
            val freeSlot = block.extents.indexOfFirst { it == null }

            if (freeSlot >= 0) {
                block.extents[freeSlot] = extent
                writeExtentBlock(blockIndex, block)
                return metadata
            }

            if (block.nextBlock == ContainerLayout.NONE_BLOCK) {
                block.nextBlock = createExtentBlock(extent)
                writeExtentBlock(blockIndex, block)
                return metadata
            }

            blockIndex = block.nextBlock
        }
    }

    fun replaceLastExtent(metadata: EntityMetadata, extent: DataExtent) {
        var blockIndex = metadata.firstExtentBlock

        while (blockIndex != ContainerLayout.NONE_BLOCK) {
            val block = readExtentBlock(blockIndex)

            if (block.nextBlock == ContainerLayout.NONE_BLOCK) {
                val slot = block.extents.indexOfLast { it != null }
                if (slot >= 0) {
                    block.extents[slot] = extent
                    writeExtentBlock(blockIndex, block)
                }
                return
            }

            blockIndex = block.nextBlock
        }
    }

    fun findLastDataExtentLocation(metadata: EntityMetadata): DataExtentLocation? =
        extentLocations(metadata).lastOrNull()

    fun findDataExtentLocation(metadata: EntityMetadata, position: Long): DataExtentLocation? =
        extentLocations(metadata).firstOrNull { location ->
            position < location.offset + capacity(location.extent)
        }

    private fun extentLocations(metadata: EntityMetadata): Sequence<DataExtentLocation> =
        sequence {
            var blockIndex = metadata.firstExtentBlock
            var offset = 0L

            while (blockIndex != ContainerLayout.NONE_BLOCK) {
                val block = readExtentBlock(blockIndex)

                for (extent in block.extents) {
                    extent ?: continue
                    yield(DataExtentLocation(extent, offset))
                    offset += capacity(extent)
                }

                blockIndex = block.nextBlock
            }
        }

    private fun createExtentBlock(extent: DataExtent): Long {
        val blockIndex = allocator.allocate(1).startBlock
        val block = DataExtentBlock(
            nextBlock = ContainerLayout.NONE_BLOCK,
            extents = arrayOfNulls(DataExtentBlockCodec.slotsPerBlock(blockSize)),
        )

        block.extents[0] = extent
        writeExtentBlock(blockIndex, block)
        return blockIndex
    }
}
