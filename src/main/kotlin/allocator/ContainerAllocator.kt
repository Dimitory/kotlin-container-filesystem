package allocator

import container.ContainerFile
import container.ContainerHeader
import container.ContainerLayout

internal class ContainerAllocator internal constructor(
    private val file : ContainerFile,
    private val header : ContainerHeader,
) : AutoCloseable {

    private val availableBlockCount: Long get() = file.size / header.blockSize
    private val managedBlockCount: Long get() = allocationMapPages.size.toLong() * blocksPerMap
    private val blocksPerMap: Long = AllocationMapPageCodec.blocksPerMap(header.blockSize)
    private val allocationMapPages = mutableListOf<CachedAllocationMapPage>()

    private data class CachedAllocationMapPage(
        val blockIndex: Long,
        var allocationMapPage: AllocationMapPage
    )

    init {
        allocationMapPages.clear()

        val visited = mutableSetOf<Long>()
        var blockIndex = ContainerLayout.FIRST_ALLOCATION_MAP_BLOCK
        val buffer = ByteArray(header.blockSize)
        while (blockIndex != ContainerLayout.NONE_BLOCK) {
            check(blockIndex in 0..<availableBlockCount) {
                "Allocation map outside container: $blockIndex"
            }
            check(visited.add(blockIndex)) {
                "Allocation map cycle|: $blockIndex"
            }
            file.read(blockIndex * header.blockSize, buffer)
            val allocationMapPage = AllocationMapPageCodec.decode(buffer)
            allocationMapPages += CachedAllocationMapPage(blockIndex, allocationMapPage)
            blockIndex = allocationMapPage.nextBlock
        }
        check(allocationMapPages.isNotEmpty()) {
            "Missing allocation map"
        }

        for ((blockIndex, _) in allocationMapPages) {
            setAllocated(blockIndex, true)
        }
        setAllocated(ContainerLayout.FIRST_METADATA_BLOCK, true)
    }

    fun allocate(requestedBlockCount: Int, preferredAfterBlock: Long = ContainerLayout.NONE_BLOCK): BlockRange {
        require(requestedBlockCount > 0)
        if (preferredAfterBlock != ContainerLayout.NONE_BLOCK) {
            val preferredStart = preferredAfterBlock + 1
            if (canAllocateRange(preferredStart, requestedBlockCount)) {
                markAllocated(preferredStart, requestedBlockCount)
                return BlockRange(preferredStart, requestedBlockCount)
            }
        }

        val freeStart = findFreeBlock(requestedBlockCount)
        if (freeStart != null) {
            markAllocated(freeStart, requestedBlockCount)
            return BlockRange(freeStart, requestedBlockCount)
        }

        while (availableBlockCount + requestedBlockCount > managedBlockCount) {
            val newMapBlock = availableBlockCount
            file.grow(header.blockSize)
            val previousIndex = allocationMapPages.lastIndex
            val previous = allocationMapPages[previousIndex]
            allocationMapPages[previousIndex] = previous.copy(
                allocationMapPage = previous.allocationMapPage.copy(
                    nextBlock = newMapBlock
                )
            )
            allocationMapPages += CachedAllocationMapPage(
                blockIndex = newMapBlock,
                allocationMapPage = AllocationMapPage(
                    nextBlock = ContainerLayout.NONE_BLOCK,
                    bitmap = ByteArray(
                        AllocationMapPageCodec.bitmapSize(header.blockSize)
                    )
                )
            )
            markAllocated(newMapBlock, 1)
        }

        val startBlock = availableBlockCount
        file.grow(requestedBlockCount * header.blockSize)
        markAllocated(startBlock, requestedBlockCount)
        return BlockRange(startBlock, requestedBlockCount)
    }

    fun free(range: BlockRange) {
        markFree(range.startBlock, range.blockCount)
    }

    private fun markAllocated(startBlock: Long, blockCount: Int) {
        repeat(blockCount) {
            setAllocated( startBlock + it, true)
        }
    }

    private fun markFree(startBlock: Long, blockCount: Int) {
        repeat(blockCount) {
            setAllocated( startBlock + it, false)
        }
    }

    private fun findFreeBlock(requestedBlockCount: Int): Long? {
        var rangeStart = ContainerLayout.NONE_BLOCK
        var rangeLength = 0
        for (block in 0..< availableBlockCount) {
            if (isAllocated(block)) {
                rangeStart = ContainerLayout.NONE_BLOCK
                rangeLength = 0
                continue
            }

            if (rangeLength == 0)
                rangeStart = block

            rangeLength++
            if (rangeLength == requestedBlockCount)
                return rangeStart
        }
        return null
    }

    private fun canAllocateRange(startBlock: Long, blockCount: Int): Boolean {
        val endBlock = startBlock + blockCount
        if (startBlock < 0 || endBlock > availableBlockCount)
            return false
        for (block in startBlock..< endBlock)
            if (isAllocated(block))
                return false
        return true
    }

    private fun isAllocated(blockIndex: Long): Boolean {
        val pageIndex = (blockIndex / blocksPerMap).toInt()
        require(pageIndex in allocationMapPages.indices)
        val cached = allocationMapPages[pageIndex]
        val relative = blockIndex % blocksPerMap
        val byteIndex = (relative ushr 3).toInt()
        val bitIndex = (relative and 7).toInt()
        val mask = 1 shl bitIndex
        return (cached.allocationMapPage.bitmap[byteIndex].toInt() and mask) != 0
    }

    private fun setAllocated(blockIndex: Long, allocated: Boolean) {
        val pageIndex = (blockIndex / blocksPerMap).toInt()
        require(pageIndex in allocationMapPages.indices)
        val cached = allocationMapPages[pageIndex]
        val relative = blockIndex % blocksPerMap
        val byteIndex = (relative ushr 3).toInt()
        val bitIndex = (relative and 7L).toInt()
        val mask = 1 shl bitIndex
        val current = cached.allocationMapPage.bitmap[byteIndex].toInt() and 0xFF
        if (allocated)
            cached.allocationMapPage.bitmap[byteIndex] = (current or mask).toByte()
        else
            cached.allocationMapPage.bitmap[byteIndex] = (current and mask.inv()).toByte()
    }

    fun read(startBlock: Long, offset: Long, buffer: ByteArray, bufferOffset: Int = 0, length: Int = buffer.size - bufferOffset) {
        validateAllocated(startBlock, offset, length)
        file.read(
            offset = header.blockSize * startBlock + offset,
            buffer = buffer,
            bufferOffset = bufferOffset,
            length = length
        )
    }

    fun write(startBlock: Long, offset: Long, buffer: ByteArray, bufferOffset: Int = 0, length: Int = buffer.size - bufferOffset) {
        validateAllocated(startBlock, offset, length)
        file.write(
            offset = header.blockSize * startBlock + offset,
            buffer = buffer,
            bufferOffset = bufferOffset,
            length = length
        )
    }

    private fun validateAllocated(startBlock: Long, offset: Long, length: Int) {
        val firstBlock = startBlock + offset / header.blockSize
        val lastBlock = startBlock + (offset + length - 1) / header.blockSize
        val count = (lastBlock - firstBlock + 1)

        require(firstBlock + count <= availableBlockCount)
        repeat(count.toInt()) { index ->
            require(isAllocated(startBlock + index)) {
                "Block ${startBlock + index} not allocated"
            }
        }
    }

    fun flush() {
        val buffer = ByteArray(header.blockSize)
        for ((blockIndex, allocationMapPage) in allocationMapPages) {
            AllocationMapPageCodec.encode(allocationMapPage, buffer)
            write(blockIndex, 0, buffer)
        }
        file.flush()
    }

    override fun close() = flush()
}
