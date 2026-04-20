package ar.com.martinrevert.aac2ac3.runner;

import java.io.File;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FfmpegRunner {
    private static final Logger log = LoggerFactory.getLogger(FfmpegRunner.class);

    private FfmpegRunner() {}

    public static int run(List<String> command, File workingDir, Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) pb.directory(workingDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        ExecutorService logPumpExecutor = Executors.newSingleThreadExecutor();
        Future<?> logPump = logPumpExecutor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[ffmpeg] {}", line);
                }
            } catch (IOException ex) {
                log.warn("Failed reading ffmpeg output stream", ex);
            }
        });

        boolean noTimeout = timeout == null || timeout.isZero() || timeout.isNegative();
        boolean finished;
        if (noTimeout) {
            p.waitFor();
            finished = true;
        } else {
            finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        if (!finished) {
            p.destroyForcibly();
            try {
                logPump.get(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            logPumpExecutor.shutdownNow();
            log.warn("ffmpeg process timed out after {} ms", timeout.toMillis());
            return -1;
        }

        try {
            logPump.get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        logPumpExecutor.shutdownNow();
        return p.exitValue();
    }
}
