package ar.com.martinrevert.aac2ac3.service;

import ar.com.martinrevert.aac2ac3.runner.FfmpegRunner;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FfmpegServiceImpl implements FfmpegService {
    private final Map<String, Boolean> protocolSupportCache = new ConcurrentHashMap<>();

    @Override
    public int run(List<String> command, File workingDir, Duration timeout) throws Exception {
        return FfmpegRunner.run(command, workingDir, timeout);
    }

    @Override
    public boolean supportsProtocol(String protocol) throws Exception {
        if (protocol == null || protocol.isBlank()) {
            return false;
        }
        String key = protocol.trim().toLowerCase(Locale.ROOT);
        Boolean cached = protocolSupportCache.get(key);
        if (cached != null) {
            return cached;
        }

        Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-protocols").start();
        String out;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            out = reader.lines().reduce("", (a, b) -> a + "\n" + b);
        }
        int rc = p.waitFor();
        boolean supported = rc == 0 && Arrays.stream(out.split("\\R"))
                .map(line -> line.trim().toLowerCase(Locale.ROOT))
                .anyMatch(line -> line.equals(key));
        protocolSupportCache.put(key, supported);
        return supported;
    }
}
