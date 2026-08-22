package metadata

enum class EntryType(val id: Byte) {
    None(0),
    File(1),
    Directory(2);

    companion object {
        fun fromByte(id: Byte): EntryType =
            entries.firstOrNull { it.id == id }
                ?: error("Unknown EntryType: $id")
    }
}
