package metadata

data class EntityMetadata(
    val id: Long,
    val parentId: Long,
    val name: String,
    val type: EntityType,
    val size: Long,
    val firstExtentBlock: Long
)