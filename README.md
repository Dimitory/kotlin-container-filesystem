# Kotlin Container File System

## Design overview
The library implements a small file-system-like storage of a single container file.

The container is split into fixed-size blocks (4096 bytes by default). 
The first bytes contain a small container header with the format magic, version and block size. 
The remaining space is managed as blocks.
File contents are read and written through streams.
Metadata and allocation information are cached in memory while the container is open and persisted when the storage is flushed or closed.

**ContainerAllocator** manages allocated blocks using bitmap-based maps.

**MetadataStorage** stores the directory hierarchy and file metadata. 
Metadata entries contain the entry name, type, parent identifier, file size and a reference to the file data.

**ContainerFileSystem** exposes the public API for creating, reading, writing, appending, deleting, renaming, moving and listing files and directories.

**DataStorage** stores file contents. File data is split into allocated block ranges and is accessed through input and output streams.

**ContainerFile** is the lowest-level abstraction over the physical container file.

## Performance

### CPU

Free-space allocation currently performs a linear scan over the
allocation bitmap, so fragmented large containers may make allocation
more expensive.

Metadata lookup is currently linear in the number of metadata entries.

### RAM
The allocation map uses approximately one bit per managed block. 
With 4 KiB blocks, a 100 GiB container requires about 3.1 MiB
for allocation bitmaps.

Disk space can have some small waste because files use fixed-size blocks. 
For example, a 10 KB file uses less than 12 KB with 4 KB blocks.

The main limit is finding free space. 
The system may need to scan the allocation map.

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
## Storage model

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

## Thread safety

A `ContainerFileSystem` is not thread-safe.

## Requirements

- JDK 17 or newer

## Running tests

```shell
# macOS / Linux
./gradlew test

# Windows
gradlew.bat test
```