package metadata

import allocator.ContainerAllocator
import container.ContainerHeader
import container.ContainerLayout

internal class MetadataStorage(
    private val allocator: ContainerAllocator,
    private val header : ContainerHeader
) : AutoCloseable {

    companion object {
        const val ROOT_ID: Long = 0L
    }

    private data class CachedBlock(
        val blockIndex: Long,
        var block: EntityMetadataBlock
    )

    private val blocks = mutableListOf<CachedBlock>()
    private val blocksByIndex = mutableMapOf<Long, CachedBlock>()
    val slotsPerBlock: Int = EntityMetadataBlockCodec.slotsPerBlock(header.blockSize)
    val root: EntityMetadata = EntityMetadata(
        id = ROOT_ID,
        parentId = ROOT_ID,
        name = "",
        type = EntityType.Directory,
        size = 0L,
        firstExtentBlock = ContainerLayout.NONE_BLOCK,
    )

    init {
        blocks.clear()
        var blockIndex = ContainerLayout.FIRST_METADATA_BLOCK
        while (blockIndex != ContainerLayout.NONE_BLOCK) {
            val buffer = ByteArray(header.blockSize)
            allocator.read(blockIndex, 0, buffer)
            val block = EntityMetadataBlockCodec.decode(buffer)
            val cachedBlock = CachedBlock(
                blockIndex = blockIndex,
                block = block
            )
            blocks += cachedBlock
            blocksByIndex[blockIndex] = cachedBlock
            blockIndex = block.nextBlock
        }
    }

    fun create(name: String, type: EntityType, parentId: Long = ROOT_ID) : EntityMetadata {
        require(name.isNotBlank())
        require(findByName(parentId, name) == null) {
            "Entry '$name' already exists"
        }

        for ((blockIndex, block) in blocks) {
            val slotIndex = block.entries.indexOfFirst { it == null }
            if (slotIndex >= 0) {
                return createInSlot(
                    block = block,
                    blockIndex = blockIndex,
                    slotIndex = slotIndex,
                    parentId = parentId,
                    name = name,
                    type = type
                )
            }
        }

        val previousBlock = blocks.last()
        val range = allocator.allocate(1, previousBlock.blockIndex)
        val newBlockIndex = range.startBlock
        val newBlock = EntityMetadataBlock(
                nextBlock = ContainerLayout.NONE_BLOCK,
                entries = arrayOfNulls(slotsPerBlock))
        previousBlock.block = previousBlock.block.copy(nextBlock = newBlockIndex)
        val cachedBlock = CachedBlock(
            blockIndex = newBlockIndex,
            block = newBlock
        )
        blocks += cachedBlock
        blocksByIndex[newBlockIndex] = cachedBlock

        return createInSlot(
            block = newBlock,
            blockIndex = newBlockIndex,
            slotIndex = 0,
            parentId = parentId,
            name = name,
            type = type
        )
    }

    fun findByName(parentId: Long, name: String): EntityMetadata? {
        for ((_, block) in blocks) {
            for (entry in block.entries) {
                if (entry != null && entry.parentId == parentId && entry.name == name) {
                    return entry
                }
            }
        }
        return null
    }

    fun findById(id: Long): EntityMetadata? {
        val blockIndex = id / slotsPerBlock
        val slotIndex = (id % slotsPerBlock).toInt()
        return blocksByIndex[blockIndex]?.block?.entries?.getOrNull(slotIndex)
    }

    fun list(parentId: Long): Sequence<EntityMetadata> =
        sequence {
            for ((_, block) in blocks) {
                for (entry in block.entries) {
                    if (entry != null && entry.parentId == parentId) {
                        yield(entry)
                    }
                }
            }
        }

    fun update(metadata: EntityMetadata) {
        val blockIndex = metadata.id / slotsPerBlock
        val slotIndex = (metadata.id % slotsPerBlock).toInt()
        val cached = requireNotNull(blocksByIndex[blockIndex])
        require(cached.block.entries[slotIndex] != null)
        cached.block.entries[slotIndex] = metadata
    }

    fun delete(id: Long) {
        val blockIndex = id / slotsPerBlock
        val slotIndex = (id % slotsPerBlock).toInt()
        val cached = requireNotNull(blocksByIndex[blockIndex])
        require(cached.block.entries[slotIndex] != null)
        cached.block.entries[slotIndex] = null
    }

    fun flush() {
        for ((blockIndex, block) in blocks) {
            val buffer = ByteArray(header.blockSize)
            EntityMetadataBlockCodec.encode(block, buffer)
            allocator.write(blockIndex, 0, buffer)
        }
    }

    fun findChild(parentId: Long, name: String): EntityMetadata? {
        return findByName(parentId, name)
    }

    override fun close() = flush()

    private fun createInSlot(block: EntityMetadataBlock,
                             blockIndex: Long,
                             slotIndex: Int,
                             parentId: Long,
                             name: String,
                             type: EntityType) : EntityMetadata
    {
        val metadata = EntityMetadata(
                id = blockIndex * slotsPerBlock + slotIndex,
                parentId = parentId,
                name = name,
                type = type,
                size = 0L,
                firstExtentBlock = ContainerLayout.NONE_BLOCK
            )
        block.entries[slotIndex] = metadata
        return metadata
    }
}
