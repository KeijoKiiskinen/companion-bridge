package fi.companion.bridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.DirectoryStream;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;

/** Bounded local log writes; no network or credentials access. */
final class PickupLogWriter {
    static final long MAX_FILE_BYTES = 20_000_000;
    static final long MAX_DIRECTORY_BYTES = 100_000_000;
    static final int MAX_DIRECTORY_ENTRIES = 2500;
    static void validatePath(Path file) throws IOException {
        if (!file.isAbsolute() || !file.equals(file.normalize())) { throw new IOException("unsafe_path"); }
        String path = file.toString();
        if (path.startsWith("\\\\") || path.startsWith("//")) { throw new IOException("network_path"); }
        for (Path part : file) {
            String name = part.toString();
            if (name.indexOf(':') >= 0 || name.endsWith(".") || name.endsWith(" ")) {
                throw new IOException("unsafe_path");
            }
        }
    }
    static void validateAttributes(BasicFileAttributes attrs) throws IOException {
        if (attrs.isSymbolicLink()) { throw new IOException("symbolic_link"); }
        // OpenJDK Windows marks junction/reparse entries as "other", even if directory is true.
        if (attrs.isOther() || (!attrs.isRegularFile() && !attrs.isDirectory())) {
            throw new IOException("special_file");
        }
    }
    static synchronized void append(Path file, String line) throws IOException {
        validatePath(file);
        // One bounded UTF-8 record per call; never allow a caller to inject extra log rows.
        if (line == null || line.length() > 65536 || !line.endsWith("\n")
                || line.indexOf('\n') != line.length() - 1 || line.indexOf('\r') >= 0) {
            throw new IOException("invalid_record");
        }
        byte[] data = line.getBytes(StandardCharsets.UTF_8);
        if (data.length > 65536) { throw new IOException("record_too_large"); }
        // Reject link/reparse redirection along the path. Not a sandbox against local tampering.
        ArrayDeque<Path> ancestors = new ArrayDeque<>();
        for (Path part = file.toAbsolutePath(); part != null; part = part.getParent()) {
            ancestors.addFirst(part);
        }
        for (Path part : ancestors) {
            try {
                validateAttributes(Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
            } catch (NoSuchFileException absent) { /* New log directories are created below. */ }
        }
        Files.createDirectories(file.getParent());
        for (Path part : ancestors) {
            try {
                validateAttributes(Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
            } catch (NoSuchFileException absent) {
                if (!part.equals(file)) { throw absent; }
            }
        }
        // Bound lifetime storage, not only one day's file. Never delete user logs.
        long total = 0;
        int entries = 0;
        boolean existing = false;
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(file.getParent())) {
            for (Path path : paths) {
                if (++entries > MAX_DIRECTORY_ENTRIES) { throw new IOException("directory_entries_limit"); }
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                validateAttributes(attrs);
                if (!attrs.isRegularFile()) { throw new IOException("unexpected_log_entry"); }
                if (path.equals(file)) { existing = true; }
                if (attrs.size() > MAX_DIRECTORY_BYTES - data.length - total) {
                    throw new IOException("directory_limit");
                }
                total += attrs.size();
            }
        }
        if (!existing && entries >= MAX_DIRECTORY_ENTRIES) { throw new IOException("directory_entries_limit"); }
        try (FileChannel stream = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            try (FileLock lock = stream.tryLock()) {
                if (lock == null) { throw new IOException("log_locked"); }
                long size = stream.size();
                if (size > MAX_FILE_BYTES - data.length) { throw new IOException("daily_limit"); }
                if (size > 0) {
                    ByteBuffer tail = ByteBuffer.allocate(1);
                    stream.position(size - 1);
                    if (stream.read(tail) != 1 || tail.array()[0] != '\n') {
                        throw new IOException("incomplete_log_tail");
                    }
                }
                stream.position(size);
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) { stream.write(buffer); }
                stream.force(false);
            } catch (OverlappingFileLockException ex) {
                throw new IOException("log_locked", ex);
            }
        }
    }
}
