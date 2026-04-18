package ar.com.martinrevert.aac2ac3.util;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ProbeUtilTest {

    @Test
    public void probe_nonZeroExit_throws() throws Exception {
        Path tmp = Files.createTempFile("x", ".mkv");
        File f = tmp.toFile();
        System.setProperty("ffprobe.path", "/bin/false");
        try {
            assertThrows(RuntimeException.class, () -> ProbeUtil.probe(f));
        } finally {
            System.clearProperty("ffprobe.path");
        }
    }

    @Test
    public void probe_nonexistentFile_throws() {
        assertThrows(IllegalArgumentException.class, () -> ProbeUtil.probe(new File("/no/such/file.mkv")));
    }
}
