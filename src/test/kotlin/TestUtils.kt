import metadata.EntityType
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.random.Random

internal object TestUtils {
    fun writeInChunks(input: InputStream, output: OutputStream, seed: Int) {
        val random = Random(seed)
        val buffer = ByteArray(8192)
        while (true) {
            val chunkSize = random.nextInt(1, buffer.size)
            val read = input.read(buffer, 0, chunkSize)
            if (read == -1)
                break
            output.write(buffer, 0, read)
        }
    }

    fun writeInChunks(stream: OutputStream, data: ByteArray, seed: Int) {
        val random = Random(seed)
        var offset = 0
        while (offset < data.size) {
            val chunkSize = random.nextInt(1, 4096 * 2)
            val length = minOf(chunkSize, data.size - offset)

            stream.write(data, offset, length)
            offset += length
        }
    }

    fun printContainerTree(container: ContainerFileSystem, path: Path, depth: Int = 0) {
        val indent = "  ".repeat(depth)
        container.list(path)
            .sortedBy { it.name }
            .forEach { entry ->
                when (entry.type) {
                    EntityType.Directory -> {
                        println("$indent ${entry.name}")
                        printContainerTree(container, path.resolve(entry.name), depth + 1)
                    }

                    EntityType.File -> {
                        println("$indent ${entry.name} (${entry.size} bytes)")
                    }

                    EntityType.None -> {}
                }
            }
    }


    fun sha256(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0)
                break
            digest.update(buffer, 0, read)
        }
        return digest.digest()
    }
}