package ar.com.martinrevert.aac2ac3.service;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public interface FfmpegService {
    int run(List<String> command, File workingDir, Path stdoutFile, Path stderrFile, Duration timeout) throws Exception;
}
