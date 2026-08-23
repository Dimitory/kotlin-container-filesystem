package streams

import container.ContainerLayout
import data.DataExtent
import data.DataExtentLocation
import data.DataStorage
import metadata.EntityMetadata
import java.io.OutputStream

class ContainerOutputStream internal constructor(
    private var metadata: EntityMetadata,
    private val storage: DataStorage,
    private val updateMetadata: (EntityMetadata) -> Unit,
) : OutputStream() {
    private var tail = storage.findLastDataExtentLocation(metadata)
    private var closed = false

    override fun write(value: Int) {
        write(byteArrayOf(value.toByte()), 0, 1)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        check(!closed) { "Stream is closed" }

        var sourceOffset = offset
        var remaining = length

        tail?.let { current ->
            val used = metadata.size - current.offset
            val available = storage.capacity(current.extent) - used

            if (available > 0) {
                val count = minOf(available, remaining.toLong()).toInt()
                storage.writeData(
                    extent = current.extent,
                    offset = used,
                    source = buffer,
                    sourceOffset = sourceOffset,
                    length = count,
                )
                metadata = metadata.copy(size = metadata.size + count)
                sourceOffset += count
                remaining -= count
            }
        }

        if (remaining > 0) {
            append(buffer, sourceOffset, remaining)
        }
    }

    override fun flush() {
        check(!closed) { "Stream is closed" }
        updateMetadata(metadata)
        storage.flush()
    }

    override fun close() {
        flush()
        closed = true
    }

    private fun append(buffer: ByteArray, offset: Int, length: Int) {
        val previous = tail
        val range = storage.allocate(
            blockCount = storage.blocksForBytes(length.toLong()),
            preferredAfterBlock = previous?.extent?.let {
                it.startBlock + it.blockCount - 1
            } ?: ContainerLayout.NONE_BLOCK
        )

        val extent: DataExtent
        val extentOffset: Long
        val writeOffset: Long

        if (previous != null && range.startBlock == previous.extent.startBlock + previous.extent.blockCount) {
            val oldCapacity = storage.capacity(previous.extent)
            extent = previous.extent.copy(
                blockCount = previous.extent.blockCount + range.blockCount
            )
            storage.replaceLastExtent(metadata, extent)
            extentOffset = previous.offset
            writeOffset = oldCapacity
        } else {
            extent = DataExtent(range.startBlock, range.blockCount)
            metadata = storage.appendExtent(metadata, extent)
            extentOffset = metadata.size
            writeOffset = 0
        }

        storage.writeData(
            extent = extent,
            offset = writeOffset,
            source = buffer,
            sourceOffset = offset,
            length = length
        )

        tail = DataExtentLocation(extent, extentOffset)
        metadata = metadata.copy(size = metadata.size + length)
    }
}
