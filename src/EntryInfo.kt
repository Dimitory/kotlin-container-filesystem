import metadata.EntryType


data class EntryInfo(
    val name: String,
    val type: EntryType,
    val size: Long
)
