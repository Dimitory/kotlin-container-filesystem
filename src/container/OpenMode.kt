package container

enum class OpenMode(val id: Byte) {
    CreateNew(0),
    CreateOrTruncate(1),
    Append(2)
}