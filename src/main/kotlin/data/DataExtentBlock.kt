package data

data class DataExtentBlock(
    var nextBlock: Long,
    @Suppress("ArrayInDataClass")
    val extents: Array<DataExtent?>
)