package container

internal object ContainerLayout {
    const val NONE_BLOCK: Long = Long.MAX_VALUE
    const val FIRST_ALLOCATION_MAP_BLOCK: Long = 0L
    const val FIRST_METADATA_BLOCK: Long = 1L
    const val SYSTEM_BLOCK_COUNT: Long = 2L
}