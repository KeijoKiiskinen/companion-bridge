package fi.companion.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real Windows filesystem checks; not a RuneLite game client or installer. */
public class WindowsSecurityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @BeforeClass public static void windowsRequired() {
        assertTrue("Real Windows required; test NOT completed",
                System.getProperty("os.name").startsWith("Windows"));
    }

    @Test public void alternateDataStreamRejected() throws Exception {
        Path base = temporary.getRoot().toPath().resolve("base.jsonl");
        Path stream = Path.of(base.toString() + ":bridge-test");
        Files.writeString(base, "original\n"); Files.writeString(stream, "test stream");
        assertEquals("test stream", Files.readString(stream));
        try { PickupLogWriter.append(stream, "new\n"); fail("ADS accepted"); }
        catch (IOException expected) { assertEquals("unsafe_path", expected.getMessage()); }
        assertEquals("original\n", Files.readString(base));
    }

    @Test public void junctionRejectedAndTargetUnchanged() throws Exception {
        Path root = temporary.getRoot().toPath();
        Path target = Files.createDirectory(root.resolve("target"));
        Path marker = target.resolve("marker.jsonl"); Files.writeString(marker, "original\n");
        Path junction = root.resolve("junction");
        // cmd expands its own script location, preserving non-ASCII/space paths.
        // The script and link/target are confined to this generated temporary folder.
        Path script = root.resolve("create-junction.bat");
        Files.writeString(script, "@echo off\r\nmklink /J \"%~dp0junction\" \"%~dp0target\"\r\nexit /b %errorlevel%\r\n",
                StandardCharsets.US_ASCII);
        Path cmd = Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe");
        String paths = cmd.toString() + script.toString();
        assertFalse("Unsafe test path", paths.chars().anyMatch(c -> c < 32 || "%!^&|<>\"".indexOf(c) >= 0));
        Path output = root.resolve("junction-output.txt");
        Process process = new ProcessBuilder(cmd.toString(), "/d", "/e:on", "/v:off", "/c", script.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly(); throw new IOException("Junction creation timed out; test NOT completed");
        }
        assertEquals("Junction creation failed; test NOT completed: " + Files.readString(output, StandardCharsets.ISO_8859_1),
                0, process.exitValue());
        try {
            try { PickupLogWriter.append(junction.resolve("marker.jsonl"), "new\n"); fail("junction accepted"); }
            catch (IOException expected) { assertEquals("special_file", expected.getMessage()); }
            assertEquals("original\n", Files.readString(marker));
        } finally { Files.deleteIfExists(junction); }
    }

    @Test public void windowsFileLockAndIncompleteTail() throws Exception {
        Path file = temporary.getRoot().toPath().resolve("pickups.jsonl");
        PickupLogWriter.append(file, "original\n");
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.WRITE); java.nio.channels.FileLock lock = channel.lock()) {
            try { PickupLogWriter.append(file, "new\n"); fail("lock accepted"); }
            catch (IOException expected) { assertEquals("log_locked", expected.getMessage()); }
        }
        assertEquals("original\n", Files.readString(file));
        Files.writeString(file, "partial");
        try { PickupLogWriter.append(file, "new\n"); fail("partial tail accepted"); }
        catch (IOException expected) { assertEquals("incomplete_log_tail", expected.getMessage()); }
        assertEquals("partial", Files.readString(file));
    }
}
