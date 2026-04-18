package ar.com.martinrevert.aac2ac3.runner;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FfmpegRunnerTest {

    @Test
    public void run_trueCommand_returnsZero() throws Exception {
        int rc = FfmpegRunner.run(List.of("true"), null, Duration.ofSeconds(2));
        assertEquals(0, rc);
    }

    @Test
    public void run_nonZeroExit_returnsCode() throws Exception {
        int rc = FfmpegRunner.run(List.of("sh", "-c", "exit 42"), null, Duration.ofSeconds(2));
        assertEquals(42, rc);
    }

    @Test
    public void run_longRunningTimesOut_returnsMinusOne() throws Exception {
        int rc = FfmpegRunner.run(List.of("sh", "-c", "sleep 5"), null, Duration.ofMillis(100));
        assertEquals(-1, rc);
    }
}
