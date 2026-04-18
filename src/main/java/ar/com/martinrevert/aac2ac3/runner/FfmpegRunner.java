package ar.com.martinrevert.aac2ac3.runner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class FfmpegRunner {
    private FfmpegRunner() {}

    public static int run(List<String> command, File workingDir, Path stdoutFile, Path stderrFile, Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) pb.directory(workingDir);
        pb.redirectOutput(stdoutFile.toFile());
        pb.redirectError(stderrFile.toFile());
        Process p = pb.start();
        boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }
}
