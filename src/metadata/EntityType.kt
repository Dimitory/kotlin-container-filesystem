package metadata

enum class EntityType(val id: Byte) {
    None(0),
    File(1),
    Directory(2);

    companion object {
        fun fromByte(id: Byte): EntityType =
            entries.firstOrNull { it.id == id }
                ?: error("Unknown EntityType: $id")
    }
}
