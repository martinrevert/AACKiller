package ar.com.martinrevert.aac2ac3.service;

import ar.com.martinrevert.aac2ac3.runner.FfmpegRunner;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@Service
public class FfmpegServiceImpl implements FfmpegService {
    @Override
    public int run(List<String> command, File workingDir, Path stdoutFile, Path stderrFile, Duration timeout) throws Exception {
        return FfmpegRunner.run(command, workingDir, stdoutFile, stderrFile, timeout);
    }
}
