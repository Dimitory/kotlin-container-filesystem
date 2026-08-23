package container

internal data class ContainerHeader(
    val magic: Int,
    val version: Short,
    val blockSize: Int) {
    companion object {
        const val MAGIC: Int = 0x52434653 // "RCFS"
        const val VERSION: Short = 1

        fun createDefault(blockSize: Int) = ContainerHeader(
            magic = MAGIC,
            version = VERSION,
            blockSize = blockSize
        )
    }
}