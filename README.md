## Performance & Scalability
CPU usage should be low because most work is reading and writing data to disk.

Memory usage is also small. The allocation map uses about 1 bit for each block. For example, a 100 GB container with 4 KB blocks needs about 3.2 MB of RAM for the map. Files are read in parts, so the whole file does not need to be loaded into memory.

Disk space can have some small waste because files use fixed-size blocks. For example, a 10 KB file uses 12 KB with 4 KB blocks.

The main limit is finding free space. The system may need to scan the allocation map. This is fine for small and medium containers, but very large containers may need a better free-space search in the future.