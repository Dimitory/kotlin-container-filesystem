import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class BasicTests {
    @Test
    fun openAndCloseFileSystem() {
        val path = Path.of("test.ds")
        try {
            val container = ContainerFileSystem.open(path)
            container.close()
        } finally {
            Files.delete(path)
        }
    }
}