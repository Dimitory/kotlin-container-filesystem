package data

import allocator.ContainerAllocator
import container.ContainerHeader
import metadata.EntityMetadataBlockCodec
import metadata.EntryMetadata
import kotlin.math.min

internal class DataStorage(
    private val allocator: ContainerAllocator,
    private val header : ContainerHeader
) : AutoCloseable {

    fun read(
        metadata: EntryMetadata,
        position: Long,
        buffer: ByteArray,
        bufferOffset: Int = 0,
        length: Int = buffer.size - bufferOffset): Int {
        TODO("Not yet implemented")
    }

    fun append(metadata: EntryMetadata,
              buffer: ByteArray?,
              bufferOffset: Int = 0,
              length: Int) {
        TODO("Not yet implemented")
    }

    fun delete(metadata: EntryMetadata) {
        TODO("Not yet implemented")
    }

    fun flush(metadata: EntryMetadata) {
        TODO("Not yet implemented")
    }

    fun flush() {
        TODO("Not yet implemented")
    }

    override fun close() {
        flush()
    }
}