package fi.companion.bridge;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

public class PickupLogWriterTest {
    @Test public void rejectsUnfinishedTailWithoutChangingIt() throws Exception {
        Path dir=Files.createTempDirectory("bridge-log-test"); Path file=dir.resolve("pickups.jsonl");
        try {
            Files.writeString(file,"{\"partial\":");
            try { PickupLogWriter.append(file,"{}\n"); fail("partial tail accepted"); }
            catch(IOException expected) { assertEquals("incomplete_log_tail",expected.getMessage()); }
            assertEquals("{\"partial\":",Files.readString(file));
        } finally { Files.deleteIfExists(file); Files.delete(dir); }
    }
    @Test public void rejectsConcurrentWriterLock() throws Exception {
        Path dir=Files.createTempDirectory("bridge-log-test"); Path file=dir.resolve("pickups.jsonl");
        try {
            Files.writeString(file,"original\n");
            try(java.nio.channels.FileChannel stream=java.nio.channels.FileChannel.open(file,java.nio.file.StandardOpenOption.WRITE);
                    java.nio.channels.FileLock lock=stream.lock()) {
                try { PickupLogWriter.append(file,"new\n"); fail("locked file accepted"); }
                catch(IOException expected) { assertEquals("log_locked",expected.getMessage()); }
            }
            assertEquals("original\n",Files.readString(file));
        } finally { Files.deleteIfExists(file); Files.delete(dir); }
    }
    @Test public void rejectsMultipleRowsAndUnsafePaths() throws Exception {
        Path dir=Files.createTempDirectory("bridge-log-test"); Path file=dir.resolve("pickups.jsonl");
        try {
            for(String line : new String[]{"no newline","one\ntwo\n","one\r\n"}) {
                try { PickupLogWriter.append(file,line); fail("invalid record accepted"); }
                catch(IOException expected) { assertEquals("invalid_record",expected.getMessage()); }
            }
            try { PickupLogWriter.append(dir.resolve("x").resolve("..").resolve("pickups.jsonl"),"new\n"); fail("traversal accepted"); }
            catch(IOException expected) { assertEquals("unsafe_path",expected.getMessage()); }
            try { PickupLogWriter.append(Path.of("relative.jsonl"),"new\n"); fail("relative path accepted"); }
            catch(IOException expected) { assertEquals("unsafe_path",expected.getMessage()); }
            assertFalse(Files.exists(file));
        } finally { Files.deleteIfExists(file); Files.delete(dir); }
    }
    @Test public void rejectsUtf8ByteLimitAndKeepsUnicode() throws Exception {
        Path dir=Files.createTempDirectory("bridge-log-test"); Path file=dir.resolve("pickups.jsonl");
        try {
            try { PickupLogWriter.append(file,"ä".repeat(32768)+"\n"); fail("UTF-8 size accepted"); }
            catch(IOException expected) { assertEquals("record_too_large",expected.getMessage()); }
            PickupLogWriter.append(file,"ääkköset\n"); assertEquals("ääkköset\n",Files.readString(file));
        } finally { Files.deleteIfExists(file); Files.delete(dir); }
    }
    @Test public void rejectsReparseEvenIfItReportsDirectory() throws Exception {
        java.nio.file.attribute.BasicFileAttributes attrs=org.mockito.Mockito.mock(java.nio.file.attribute.BasicFileAttributes.class);
        org.mockito.Mockito.when(attrs.isDirectory()).thenReturn(true);
        org.mockito.Mockito.when(attrs.isOther()).thenReturn(true);
        try { PickupLogWriter.validateAttributes(attrs); fail("reparse directory accepted"); }
        catch(IOException expected) { assertEquals("special_file",expected.getMessage()); }
    }
    @Test public void rejectsLifetimeStorageLimit() throws Exception {
        Path dir=Files.createTempDirectory("bridge-log-test"); Path old=dir.resolve("old.jsonl");
        Path file=dir.resolve("pickups.jsonl");
        try {
            try(java.io.RandomAccessFile stream=new java.io.RandomAccessFile(old.toFile(),"rw")) {
                stream.setLength(PickupLogWriter.MAX_DIRECTORY_BYTES);
            }
            try { PickupLogWriter.append(file,"new\n"); fail("directory limit accepted"); }
            catch(IOException expected) { assertEquals("directory_limit",expected.getMessage()); }
            assertFalse(Files.exists(file));
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(old); Files.delete(dir); }
    }
    @Test public void rejectsLinkedAncestor() throws Exception {
        org.junit.Assume.assumeFalse("Windows junction is covered separately without admin rights",
                System.getProperty("os.name").startsWith("Windows"));
        Path dir=Files.createTempDirectory("bridge-log-test");Path target=dir.resolve("target");
        Path link=dir.resolve("link");Files.createDirectory(target);Files.createSymbolicLink(link,target);
        try {
            try { PickupLogWriter.append(link.resolve("pickups.jsonl"),"new\n"); fail("ancestor accepted"); }
            catch(IOException expected) { assertEquals("symbolic_link",expected.getMessage()); }
            assertFalse(Files.exists(target.resolve("pickups.jsonl")));
        } finally { Files.delete(link);Files.delete(target);Files.delete(dir); }
    }
    @Test public void appendsBoundedRecord() throws Exception {
        Path dir=Files.createTempDirectory("bridge-log-test"); Path file=dir.resolve("pickups.jsonl");
        try { PickupLogWriter.append(file,"first\n"); PickupLogWriter.append(file,"second\n");
            assertEquals("first\nsecond\n", Files.readString(file));
        } finally { Files.deleteIfExists(file); Files.delete(dir); }
    }
    @Test public void rejectsOversizedRecord() throws Exception {
        Path dir=Files.createTempDirectory("bridge-log-test"); Path file=dir.resolve("pickups.jsonl");
        try { try { PickupLogWriter.append(file,"x".repeat(65537)); fail("size accepted"); }
            catch(IOException expected) { assertFalse(Files.exists(file)); }
        } finally { Files.deleteIfExists(file); Files.delete(dir); }
    }
    @Test public void rejectsSymlink() throws Exception {
        org.junit.Assume.assumeFalse("Windows junction is covered separately without admin rights",
                System.getProperty("os.name").startsWith("Windows"));
        Path dir=Files.createTempDirectory("bridge-log-test"); Path target=dir.resolve("target"); Path file=dir.resolve("link");
        try { Files.writeString(target,"unchanged"); Files.createSymbolicLink(file,target);
            try { PickupLogWriter.append(file,"new\n"); fail("link accepted"); } catch(IOException expected) { }
            assertEquals("unchanged",Files.readString(target));
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(target); Files.delete(dir); }
    }
    @Test public void rejectsDailySizeLimit() throws Exception {
        Path dir=Files.createTempDirectory("bridge-log-test"); Path file=dir.resolve("pickups.jsonl");
        try { try(java.io.RandomAccessFile stream=new java.io.RandomAccessFile(file.toFile(),"rw")) {
                stream.setLength(PickupLogWriter.MAX_FILE_BYTES);
            }
            try { PickupLogWriter.append(file,"new\n"); fail("daily limit accepted"); } catch(IOException expected) { }
            assertEquals(PickupLogWriter.MAX_FILE_BYTES,Files.size(file));
        } finally { Files.deleteIfExists(file); Files.delete(dir); }
    }
}
