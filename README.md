## Performance
CPU utilization is actually low most work is reading and writing data to disk.

Library uses allocation map about 1 bit for each block. For example, a 100 GB container with 4 KB blocks needs about 3.2 MB of RAM for the map. Files are read in parts, so the whole file does not need to be loaded into memory.

Disk space can have some small waste because files use fixed-size blocks. For example, a 10 KB file uses less than 12 KB with 4 KB blocks.

The main limit is finding free space. The system may need to scan the allocation map. This is fine for small and medium containers, but for large containers may need a better free-space search in the future.

## Example
```kotlin
val containerPath = Path.of("storage.rcfs")

ContainerFileSystem.open(containerPath).use { container ->
    container.openWrite(Path.of("documents/hello.txt")).use { output ->
        output.write("Hello!".toByteArray())
    }
}

ContainerFileSystem.open(containerPath).use { container ->
    val content = container
        .openRead(Path.of("documents/hello.txt"))
        .use { it.readBytes() }
    println(content.decodeToString())
}
```
## Storage layout

```text
+--------------------------------------+
| Container header                     | 
| Magic                  | 4 bytes     |
| Version                | 2 bytes     |
| Block size             | 4 bytes     | 10 bytes
+--------------------------------------+
| Allocation map block                 |
| Next map block         | 8 bytes     |
| Allocation bitmap      | 4088 bytes  | 4096 bytes
+--------------------------------------+
| Metadata block                       | 
| Next metadata block    | 8 bytes     |
| Used slots bitmap      | 8 bytes     |
| Metadata entries       | remaining   | 4096 bytes
+--------------------------------------+
| Data block                           |  
| Next map block         | 8 bytes     |
| Allocation bitmap      | 4088 bytes  | 4096 bytes
+--------------------------------------+
| Data block                           |
+--------------------------------------+
| ...                                  |
+--------------------------------------+
| Metadata | Allocation | Data blocks..|
+--------------------------------------+
```