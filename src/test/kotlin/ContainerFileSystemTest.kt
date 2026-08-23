import metadata.EntityType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlin.random.Random

class ContainerFileSystemTest {
    @TempDir
    lateinit var tempDir: Path

    private val projectRoot: Path get() = Path.of("").normalize()

    @Test
    fun writeFilesByChunksAndReopen() {
        val containerPath = tempDir.resolve("test.rcfs")

        val files = arrayOf(
            Path.of("files/one.bin") to Random(1).nextBytes(1024),
            Path.of("files/two.bin") to Random(2).nextBytes(1024 * 2),
            Path.of("files/three.bin") to Random(3).nextBytes(1024 * 4),
            Path.of("files/four.bin") to Random(4).nextBytes(1024 * 6),
            Path.of("files/five.bin") to Random(5).nextBytes(1024 * 8),
            Path.of("files/huge.bin") to Random(5).nextBytes(1024 * 1024 * 8),
        )

        ContainerFileSystem.open(containerPath).use { container ->
            files.forEachIndexed { index, (path, data) ->
                container.openWrite(path).use { stream ->
                    writeInChunks(stream, data, index)
                }
            }
        }

        ContainerFileSystem.open(containerPath).use { container ->
            files.forEach { (path, expected) ->
                val actual = container.openRead(path).use { stream ->
                    stream.readBytes()
                }
                assertArrayEquals(expected, actual, "Content differs for $path")
            }
        }
    }

    @Test
    fun storeProjectTreeFunctionalTest() {
        val containerPath = tempDir.resolve("project.rcfs")
        val files = requestProjectFiles()
        ContainerFileSystem.open(containerPath).use { container ->
            files.forEachIndexed { index, file ->
                Files.newInputStream(file).use { input ->
                    container.openWrite(file).use { output ->
                        writeInChunks(input, output, seed = index)
                    }
                }
            }
        }

        ContainerFileSystem.open(containerPath).use { container ->
            files.forEach { file ->
                val expectedChecksum = Files.newInputStream(file).use(::sha256)
                val containerChecksum = container.openRead(file).use(::sha256)
                assertArrayEquals(expectedChecksum, containerChecksum)
            }
            println("initial status")
            printContainerTree(container, Path.of(""))
        }


        val deletedFiles = files
            .shuffled(Random(7))
            .take((files.size * 0.7).roundToInt())
            .toSet()

        ContainerFileSystem.open(containerPath).use { container ->
            deletedFiles.forEach { file ->
                container.delete(file)
            }
            println("after deleting")
            printContainerTree(container, Path.of(""))
        }

        ContainerFileSystem.open(containerPath).use { container ->
            files.forEachIndexed { index, file ->
                if (!container.exists(file)) {
                    Files.newInputStream(file).use { input ->
                        container.openWrite(file).use { output ->
                            writeInChunks(input, output, seed = index)
                        }
                    }
                }
                val expectedChecksum = Files.newInputStream(file).use(::sha256)
                val containerChecksum = container.openRead(file).use(::sha256)
                assertArrayEquals(expectedChecksum, containerChecksum)
            }
            println("after adding files")
            printContainerTree(container, Path.of(""))
        }

        ContainerFileSystem.open(containerPath).use { container ->
            files.forEach { file ->
                val expectedChecksum = Files.newInputStream(file).use(::sha256)
                val containerChecksum = container.openRead(file).use(::sha256)
                assertArrayEquals(expectedChecksum, containerChecksum)
            }
        }
    }

    private fun requestProjectFiles(): List<Path> {
        val excludedDirectories = setOf(".git", ".gradle", ".idea", ".kotlin", "build", "gradle", "out")
        return Files.walk(projectRoot).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { path ->
                    val relative = projectRoot.relativize(path)
                    relative.none { component ->
                        component.toString() in excludedDirectories
                    }
                }
                .toList()
        }
    }

    private fun writeInChunks(input: InputStream, output: OutputStream, seed: Int) {
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

    private fun writeInChunks(stream: OutputStream, data: ByteArray, seed: Int) {
        val random = Random(seed)
        var offset = 0
        while (offset < data.size) {
            val chunkSize = random.nextInt(1, 4096 * 2)
            val length = minOf(chunkSize, data.size - offset)

            stream.write(data, offset, length)
            offset += length
        }
    }

    private fun sha256(input: InputStream): ByteArray {
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

    private fun printContainerTree(container: ContainerFileSystem, path: Path, depth: Int = 0) {
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
}