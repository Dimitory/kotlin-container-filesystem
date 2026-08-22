package metadata

data class EntityMetadataBlock(
    val nextBlock: Long,
    @Suppress("ArrayInDataClass")
    val entries: Array<EntryMetadata?>
)