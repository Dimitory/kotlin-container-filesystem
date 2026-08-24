import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.println
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.use

class FunctionalTest {
    @TempDir
    lateinit var tempDir: Path

    private val projectRoot: Path get() = Path.of("").normalize()

    @Suppress("ArrayInDataClass")
    private data class ProjectFile(
        val sourcePath: Path,
        val relativePath: Path,
        val size: Long,
        val checksum: ByteArray
    )

    @Test
    fun storeProjectTreeFunctionalTest() {
        val containerPath = tempDir.resolve("project.rcfs")
        val files = requestProjectFiles()

        ContainerFileSystem.open(containerPath).use { container ->
            files.forEachIndexed { index, file ->
                Files.newInputStream(file.sourcePath).use { input ->
                    container.openWrite(file.relativePath).use { output ->
                        TestUtils.writeInChunks(input, output, index)
                    }
                }
            }
        }

        ContainerFileSystem.open(containerPath).use { container ->
            files.forEach { file ->
                val containerChecksum = container.openRead(file.sourcePath).use{ input -> TestUtils.sha256(input) }
                assertArrayEquals(file.checksum, containerChecksum)
            }
            println("initial status")
            TestUtils.printContainerTree(container, Path.of(""))
        }

        val deletedFiles = files
            .shuffled(Random(7))
            .take((files.size * 0.7).roundToInt())
            .mapTo(mutableSetOf()) { it.relativePath }
            .toSet()

        ContainerFileSystem.open(containerPath).use { container ->
            deletedFiles.forEach { path ->
                container.delete(path)
            }
            println("after deleting")
            TestUtils.printContainerTree(container, Path.of(""))
        }

        ContainerFileSystem.open(containerPath).use { container ->
            files.forEachIndexed { index, file ->
                val destination = Path.of("restored").resolve(file.relativePath)
                Files.newInputStream(file.sourcePath).use { input ->
                    container.openWrite(destination).use { output ->
                        TestUtils.writeInChunks(input, output, 1000 + index)
                    }
                }
            }
            println("after adding files")
            TestUtils.printContainerTree(container, Path.of(""))
        }

        ContainerFileSystem.open(containerPath).use { container ->
            files.forEach { file ->
                if (file.relativePath in deletedFiles) {
                    assertFalse(container.exists(file.relativePath))
                    val destination = Path.of("restored").resolve(file.relativePath)
                    val containerChecksum = container.openRead(destination).use{ input -> TestUtils.sha256(input) }
                    assertArrayEquals(file.checksum, containerChecksum)
                } else {
                    val containerChecksum = container.openRead(file.sourcePath).use { input -> TestUtils.sha256(input) }
                    assertArrayEquals(file.checksum, containerChecksum)
                }
            }
        }
    }

    private fun requestProjectFiles(): List<ProjectFile> {
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
                .map { sourcePath ->
                    ProjectFile(
                        sourcePath = sourcePath,
                        relativePath =
                            projectRoot.relativize(sourcePath),
                        size = Files.size(sourcePath),
                        checksum = Files.newInputStream(sourcePath)
                            .use { input -> TestUtils.sha256(input) }
                    )
                }
                .toList()
        }
    }
}