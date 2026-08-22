package metadata

data class EntryMetadata(
    val id: Long,
    val parentId: Long,
    val name: String,
    val type: EntryType,
    val size: Long,
    val firstExtentBlock: Long
)