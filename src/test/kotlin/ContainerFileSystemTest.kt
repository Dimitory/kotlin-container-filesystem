import metadata.EntityType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.random.Random

class ContainerFileSystemTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun createWriteReadAndReopen() {
        val containerPath = tempDir.resolve("test.rcfs")

        val files = listOf(
            Path.of("files/1.bin") to Random(1).nextBytes(1),
            Path.of("files/4095.bin") to Random(2).nextBytes(4095),
            Path.of("files/4096.bin") to Random(3).nextBytes(4096),
            Path.of("files/4097.bin") to Random(4).nextBytes(4097),
            Path.of("files/8191.bin") to Random(5).nextBytes(8191),
            Path.of("files/8192.bin") to Random(6).nextBytes(8192),
            Path.of("files/8193.bin") to Random(7).nextBytes(8193),
        )

        ContainerFileSystem.open(containerPath).use { container ->
            files.forEachIndexed { index, (path, data) ->
                container.openWrite(path).use { output ->
                    TestUtils.writeInChunks(output, data, seed = index)
                }
            }
        }

        ContainerFileSystem.open(containerPath).use { container ->
            files.forEach { (path, expected) ->
                val actual = container.openRead(path).use { it.readBytes() }
                assertArrayEquals(expected, actual)
            }
        }
    }


    @Test
    fun appendToExistingFile() {
        val containerPath = tempDir.resolve("test.rcfs")
        val path = Path.of("file.txt")

        ContainerFileSystem.open(containerPath).use { container ->
            container.openWrite(path).use {
                it.write("Hello".toByteArray())
            }

            container.openWrite(path, OpenMode.Append).use {
                it.write(" World".toByteArray())
            }
        }

        ContainerFileSystem.open(containerPath).use { container ->
            val actual = container.openRead(path).use { it.readBytes().decodeToString() }
            assertEquals("Hello World", actual)
        }
    }

    @Test
    fun createOrTruncateReplacesContent() {
        val containerPath = tempDir.resolve("test.rcfs")
        val path = Path.of("file.txt")

        ContainerFileSystem.open(containerPath).use { container ->
            container.openWrite(path).use {
                it.write("Hello".toByteArray())
            }

            container.openWrite(path, OpenMode.CreateOrTruncate).use {
                it.write(" World".toByteArray())
            }
        }

        ContainerFileSystem.open(containerPath).use { container ->
            val actual = container.openRead(path)
                .use { it.readBytes().decodeToString() }

            assertEquals(" World", actual)
        }
    }

    @Test
    fun deleteFile() {
        val containerPath = tempDir.resolve("test.rcfs")
        val path = Path.of("files/deleted.bin")

        ContainerFileSystem.open(containerPath).use { container ->
            container.openWrite(path).use {
                it.write(ByteArray(1024))
            }
            assertTrue(container.exists(path))
            container.delete(path)
            assertFalse(container.exists(path))
        }

        ContainerFileSystem.open(containerPath).use { container ->
            assertFalse(container.exists(path))
        }
    }


    @Test
    fun renameFile() {
        val containerPath = tempDir.resolve("test.rcfs")
        val oldPath = Path.of("files/old.txt")
        val newPath = Path.of("files/new.txt")
        val expected = "Hello world".toByteArray()

        ContainerFileSystem.open(containerPath).use { container ->
            container.openWrite(oldPath).use {
                it.write(expected)
            }

            container.move(oldPath, newPath)
            assertFalse(container.exists(oldPath))
            assertTrue(container.exists(newPath))
        }

        ContainerFileSystem.open(containerPath).use { container ->
            assertFalse(container.exists(oldPath))
            assertArrayEquals(expected, container.openRead(newPath).use { it.readBytes() })
        }
    }


    @Test
    fun moveFile() {
        val containerPath = tempDir.resolve("test.rcfs")
        val source = Path.of("source/file.txt")
        val destination = Path.of("destination")
        val expected = "Hello world".toByteArray()

        ContainerFileSystem.open(containerPath).use { container ->
            container.openWrite(source).use {
                it.write(expected)
            }
            container.move(source, destination)
            assertFalse(container.exists(source))
            assertTrue(container.exists(destination))
        }

        ContainerFileSystem.open(containerPath).use { container ->
            assertArrayEquals(expected, container.openRead(destination).use { it.readBytes() })
        }
    }

    @Test
    fun deleteRecursively() {
        val containerPath = tempDir.resolve("test.rcfs")
        val expected = "Hello world".toByteArray()

        ContainerFileSystem.open(containerPath).use { container ->
            container.openWrite(Path.of("root.txt")).use {
                it.write(expected)
            }
            container.openWrite(Path.of("sub/one.txt")).use {
                it.write(expected)
            }
            container.openWrite(Path.of("sub/one/two.txt")).use {
                it.write(expected)
            }
            container.openWrite(Path.of("sub/one/two/three.txt")).use {
                it.write(expected)
            }
            container.delete(Path.of("sub"),true)
            assertFalse(container.exists(Path.of("sub")))
            assertFalse(container.exists(Path.of("sub/one.txt")))
            assertFalse(container.exists(Path.of("sub/one/two.txt")))
            assertFalse(container.exists(Path.of("sub/one/two/three.txt")))
            assertTrue(container.exists(Path.of("root.txt")))
        }
    }

    @Test
    fun list() {
        val containerPath = tempDir.resolve("test.rcfs")
        val expected = "Hello world".toByteArray()

        ContainerFileSystem.open(containerPath).use { container ->
            container.openWrite(Path.of("root.txt")).use {
                it.write(expected)
            }
            container.openWrite(Path.of("sub/one.txt")).use {
                it.write(expected)
            }
            container.openWrite(Path.of("sub/nested/two.txt")).use {
                it.write(expected)
            }
            container.openWrite(Path.of("sub/nested/three.txt")).use {
                it.write(expected)
            }

            val entries = container.list(Path.of("sub"))
                .sortedBy { it.name }
                .toList()

            assertEquals(listOf("nested", "one.txt"), entries.map { it.name })
            assertEquals(EntityType.Directory, entries[0].type)
            assertEquals(EntityType.File, entries[1].type)
        }
    }

    @Test
    fun getFileInfo() {
        val containerPath = tempDir.resolve("test.rcfs")
        val path = Path.of("test.bin")
        val expected = "Hello world".toByteArray()

        ContainerFileSystem.open(containerPath).use { container ->
            container.openWrite(path).use {
                it.write(expected)
            }

            val info = container.getInfo(path)
            assertNotNull(info)
            assertEquals("test.bin", info.name)
            assertEquals(EntityType.File, info.type)
            assertEquals(expected.size.toLong(), info.size)
        }
    }
}