package allocator

internal data class AllocationMapPage(
    val nextBlock: Long,
    @Suppress("ArrayInDataClass")
    val bitmap: ByteArray,
)