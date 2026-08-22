package metadata

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

internal object EntryMetadataCodec {
    const val NAME_MAX_BYTES = 256
    const val SERIALIZED_SIZE: Int =
        Long.SIZE_BYTES +   // id
        Long.SIZE_BYTES +   // parentId
        Short.SIZE_BYTES +  // name byte length
        NAME_MAX_BYTES +    // name data
        Byte.SIZE_BYTES +   // type
        Long.SIZE_BYTES +   // size
        Long.SIZE_BYTES     // firstExtentBlock

    fun encode(value: EntryMetadata, destination: ByteArray, offset: Int = 0) {
        require(offset >= 0)
        require(offset + SERIALIZED_SIZE <= destination.size)
        val nameBytes = value.name.toByteArray(StandardCharsets.UTF_8)
        require(nameBytes.size <= NAME_MAX_BYTES)
        ByteBuffer
            .wrap(destination, offset, SERIALIZED_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(value.id)
            .putLong(value.parentId)
            .putShort(nameBytes.size.toShort())
            .put(nameBytes)
            .position(
            offset +
                    Long.SIZE_BYTES +
                    Long.SIZE_BYTES +
                    Short.SIZE_BYTES +
                    NAME_MAX_BYTES)
            .put(value.type.id)
            .putLong(value.size)
            .putLong(value.firstExtentBlock)
    }

    fun decode(source: ByteArray, offset: Int = 0): EntryMetadata {
        require(offset >= 0)
        require(offset + SERIALIZED_SIZE <= source.size)
        val buffer = ByteBuffer
            .wrap(source, offset, SERIALIZED_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        val id = buffer.long
        val parentId = buffer.long
        val nameLength = buffer.short.toInt() and 0xFFFF
        require(nameLength <= NAME_MAX_BYTES)
        val nameBytes = ByteArray(nameLength)
        buffer.get(nameBytes)
        buffer.position(
            offset +
                    Long.SIZE_BYTES +
                    Long.SIZE_BYTES +
                    Short.SIZE_BYTES +
                    NAME_MAX_BYTES)
        val type = EntryType.fromByte(buffer.get())
        val size = buffer.long
        val firstExtentBlock = buffer.long

        return EntryMetadata(
            id = id,
            parentId = parentId,
            name = String(nameBytes, StandardCharsets.UTF_8),
            type = type,
            size = size,
            firstExtentBlock = firstExtentBlock
        )
    }
}