package ar.com.martinrevert.aac2ac3.service;

import java.io.File;
import java.time.Duration;
import java.util.List;

public interface FfmpegService {
    int run(List<String> command, File workingDir, Duration timeout) throws Exception;

    boolean supportsProtocol(String protocol) throws Exception;
}
