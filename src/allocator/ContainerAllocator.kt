package allocator

import container.ContainerFile
import container.ContainerHeader
import container.ContainerLayout
import metadata.EntityMetadataBlock
import kotlin.Int

internal class ContainerAllocator internal constructor(
    private val file : ContainerFile,
    private val header : ContainerHeader,
) : AutoCloseable {

    public val bitmapSize: Int = AllocationMapPageCodec.bitmapSize(header.blockSize)
    private val blocksPerMap: Long = AllocationMapPageCodec.blocksPerMap(header.blockSize)
    private val availableBlockCount: Long get() = file.size / header.blockSize
    private val allocationMapPages = mutableListOf<CachedAllocationMapPage>()

    private data class CachedAllocationMapPage(
        val blockIndex: Long,
        var allocationMapPage: AllocationMapPage
    )

    init {
        allocationMapPages.clear()

        var blockIndex = ContainerLayout.FIRST_ALLOCATION_MAP_BLOCK
        val buffer = ByteArray(header.blockSize)
        while (blockIndex != ContainerLayout.NONE_BLOCK) {
            file.read(blockIndex * header.blockSize, buffer)
            val allocationMapPage = AllocationMapPageCodec.decode(buffer)
            allocationMapPages += CachedAllocationMapPage(blockIndex, allocationMapPage)
            blockIndex = allocationMapPage.nextBlock
        }
        for ((blockIndex, _) in allocationMapPages) {
            setAllocated(blockIndex, true);
        }
        setAllocated(ContainerLayout.FIRST_METADATA_BLOCK, true)
        validateAllocationMap()
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

        val startBlock = availableBlockCount
        file.grow(requestedBlockCount * header.blockSize)
        markAllocated(startBlock, requestedBlockCount)
        return BlockRange(startBlock,requestedBlockCount)
    }

    fun free(range: BlockRange) {
        require(range.blockCount > 0)
        require(range.startBlock >= 0)
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
                rangeLength = 0;
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
        if (startBlock < 0 || endBlock > availableBlockCount) return false
        if (endBlock > allocationMapPages.size.toLong() * blocksPerMap) return false
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
        val touchedBlocks = ((offset + length + header.blockSize - 1L) / header.blockSize).toInt()
        validateAllocated(startBlock, touchedBlocks)
        file.read(
            offset = header.blockSize * startBlock + offset,
            buffer = buffer,
            bufferOffset = bufferOffset,
            length = length
        )
    }

    fun write(startBlock: Long, offset: Long, buffer: ByteArray, bufferOffset: Int = 0, length: Int = buffer.size - bufferOffset) {
        val touchedBlocks = ((offset + length + header.blockSize - 1L) / header.blockSize).toInt()
        validateAllocated(startBlock, touchedBlocks)
        file.write(
            offset = header.blockSize * startBlock + offset,
            buffer = buffer,
            bufferOffset = bufferOffset,
            length = length
        )
    }


    fun readBlock(blockIndex: Long, offset: Long, buffer: ByteArray, bufferOffset: Int = 0, length: Int = buffer.size - bufferOffset) =
        read(blockIndex, offset, buffer, bufferOffset, length)

    fun writeBlock(blockIndex: Long, offset: Long, buffer: ByteArray, bufferOffset: Int = 0, length: Int = buffer.size - bufferOffset) =
        write(blockIndex, offset, buffer, bufferOffset, length)

    private fun validateAllocated(blockIndex: Long, count: Int = 1) {
        require(blockIndex + count <= availableBlockCount) {
            "Block range is outside container"
        }

        repeat(count) { index ->
            require(isAllocated(blockIndex + index)) {
                "Block ${blockIndex + index} is not allocated"
            }
        }
    }

    private fun validateAllocationMap() {
        require(allocationMapPages.isNotEmpty()) {
            "Allocation map is empty"
        }

        require(allocationMapPages.first().blockIndex == ContainerLayout.FIRST_ALLOCATION_MAP_BLOCK) {
            "Invalid first allocation map block: ${allocationMapPages.first().blockIndex}"
        }

        for (index in allocationMapPages.indices) {
            val page = allocationMapPages[index]
            require(page.blockIndex in 0..<availableBlockCount) {
                "Allocation map page points outside container: ${page.blockIndex}"
            }

            require(isAllocated(page.blockIndex)) {
                "Allocation map block ${page.blockIndex} is marked as free"
            }

            val expectedNext = allocationMapPages.getOrNull(index + 1)?.blockIndex ?: ContainerLayout.NONE_BLOCK
            require(page.allocationMapPage.nextBlock == expectedNext) {
                "Invalid allocation map chain at block ${page.blockIndex}: expected next=${expectedNext}, actual=${page.allocationMapPage.nextBlock}"
            }

            require(page.allocationMapPage.bitmap.size == bitmapSize) {
                "Invalid allocation map bitmap size at block ${page.blockIndex}: ${page.allocationMapPage.bitmap.size}"
            }
        }

        val maxManagedBlocks = allocationMapPages.size.toLong() * blocksPerMap
        require(availableBlockCount <= maxManagedBlocks) {
            "Allocation map does not cover entire container: blocks=${availableBlockCount}, managed=${maxManagedBlocks}"
        }
    }

    fun flush() {
        val buffer = ByteArray(header.blockSize)
        for ((blockIndex, allocationMapPage) in allocationMapPages) {
            AllocationMapPageCodec.encode(allocationMapPage, buffer);
            writeBlock(blockIndex, 0, buffer);
        }
        file.flush()
    }

    override fun close() = flush()
}
