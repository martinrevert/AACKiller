package ar.com.martinrevert.aac2ac3.runner;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FfmpegRunnerTest {

    @Test
    public void run_trueCommand_returnsZero() throws Exception {
        Path stdout = Files.createTempFile("ffout", ".log");
        Path stderr = Files.createTempFile("fferr", ".log");

        int rc = FfmpegRunner.run(List.of("true"), null, stdout, stderr, Duration.ofSeconds(2));
        assertEquals(0, rc);
    }

    @Test
    public void run_nonZeroExit_returnsCode() throws Exception {
        Path stdout = Files.createTempFile("ffout2", ".log");
        Path stderr = Files.createTempFile("fferr2", ".log");

        int rc = FfmpegRunner.run(List.of("sh", "-c", "exit 42"), null, stdout, stderr, Duration.ofSeconds(2));
        assertEquals(42, rc);
    }

    @Test
    public void run_longRunningTimesOut_returnsMinusOne() throws Exception {
        Path stdout = Files.createTempFile("ffout3", ".log");
        Path stderr = Files.createTempFile("fferr3", ".log");

        int rc = FfmpegRunner.run(List.of("sh", "-c", "sleep 5"), null, stdout, stderr, Duration.ofMillis(100));
        assertEquals(-1, rc);
    }
}
