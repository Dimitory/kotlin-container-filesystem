import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.random.Random

class ContainerFileSystemTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun openWriteCloseAndReopen() {
        val containerPath = tempDir.resolve("test.fs")
        val expected = Random(42).nextBytes(4096)
        ContainerFileSystem.open(containerPath).use { container ->
            container.openWrite(Path.of("video/test.mp4")).use { stream ->
                stream.write(expected)
            }
        }
        ContainerFileSystem.open(containerPath).use { container ->
            val actual = container.openRead(Path.of("video/test.mp4")).use { stream ->
                stream.readBytes()
            }
            assertArrayEquals(expected, actual)
        }
    }

    @Test
    fun writeFilesAcrossBlockBoundary() {
        val containerPath = tempDir.resolve("test.fs")

        val files = mapOf(
            Path.of("files/one.bin") to Random(1).nextBytes(1024),
            Path.of("files/two.bin") to Random(2).nextBytes(4096),
            Path.of("files/three.bin") to Random(3).nextBytes(4096 * 2),
            Path.of("files/four.bin") to Random(4).nextBytes(4096 * 3),
            Path.of("files/five.bin") to Random(5).nextBytes(4096 * 4),
        )

        ContainerFileSystem.open(containerPath).use { container ->
            files.forEach { (path, data) ->
                container.openWrite(path).use { stream ->
                    stream.write(data)
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
    fun writeVideoCloseReopenAndRead() {
        val containerPath = tempDir.resolve("test.fs")
        val sourcePath = Path.of("data/Rick_Astley_Never_Gonna_Give_You_Up.mp4")
        val containerFileName = Path.of("video/Rick_Astley_Never_Gonna_Give_You_Up.mp4")

        val expectedHash = Files.newInputStream(sourcePath).use {
            sha256(it)
        }

        ContainerFileSystem.open(containerPath).use { container ->
            Files.newInputStream(sourcePath).use { input ->
                container.openWrite(containerFileName).use { output ->
                    input.copyTo(output)
                }
            }
        }

        ContainerFileSystem.open(containerPath).use { container ->
            container.openRead(containerFileName).use { input ->
                assertArrayEquals(expectedHash, sha256(input))
            }
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
}