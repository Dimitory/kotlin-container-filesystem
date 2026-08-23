package streams

import data.DataExtentLocation
import data.DataStorage
import metadata.EntityMetadata
import java.io.InputStream

class ContainerInputStream internal constructor(
    private val metadata: EntityMetadata,
    private val storage: DataStorage,
) : InputStream() {
    private var position = 0L
    private var tail: DataExtentLocation? = null
    private var closed = false

    override fun read(): Int {
        val byte = ByteArray(1)
        return if (read(byte) == -1) -1 else byte[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "Stream is closed" }
        if (position >= metadata.size) return -1

        var bufferOffset = offset
        var remaining = minOf(length.toLong(), metadata.size - position)

        while (remaining > 0) {
            val current = findExtent() ?: break
            val extentOffset = position - current.offset
            val count = minOf(
                remaining,
                storage.capacity(current.extent) - extentOffset
            ).toInt()

            storage.readData(
                extent = current.extent,
                offset = extentOffset,
                destination = buffer,
                destinationOffset = bufferOffset,
                length = count
            )

            position += count
            bufferOffset += count
            remaining -= count

            if (position == current.offset + storage.capacity(current.extent))
                tail = null
        }

        return bufferOffset - offset
    }

    override fun skip(count: Long): Long {
        check(!closed) { "Stream is closed" }
        val skipped = minOf(count, metadata.size - position)
        position += skipped
        tail = null
        return skipped
    }

    override fun close() {
        closed = true
    }

    private fun findExtent(): DataExtentLocation? {
        if (tail == null)
            tail = storage.findDataExtentLocation(metadata, position)
        return tail
    }
}
