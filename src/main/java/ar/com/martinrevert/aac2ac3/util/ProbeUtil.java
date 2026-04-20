package ar.com.martinrevert.aac2ac3.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class ProbeUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProbeUtil() {}

    /**
     * Run ffprobe to get audio stream info as JsonNode.
     */
    public static JsonNode probe(File file) throws Exception {
        if (!file.exists()) throw new IllegalArgumentException("file not found: " + file);
        return probePath(file.getAbsolutePath());
    }

    public static JsonNode probePath(String inputPath) throws Exception {
        if (inputPath == null || inputPath.isBlank()) {
            throw new IllegalArgumentException("inputPath is required");
        }

        // allow overriding ffprobe executable path for testing or env differences
        String ffprobe = System.getProperty("ffprobe.path");
        if (ffprobe == null || ffprobe.isBlank()) ffprobe = "ffprobe";

        ProcessBuilder pb = new ProcessBuilder(
            ffprobe,
            "-v", "error",
            "-select_streams", "a",
            "-show_entries", "stream=index,codec_name,channels:format=duration",
            "-of", "json",
            inputPath
        );

        Process p = pb.start();
        try (BufferedReader outReader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
             BufferedReader errReader = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String out = outReader.lines().collect(Collectors.joining("\n"));
            String err = errReader.lines().collect(Collectors.joining("\n"));
            int rc = p.waitFor();
            if (rc != 0) {
                String msg = "ffprobe failed (rc=" + rc + ")";
                if (!err.isBlank()) {
                    msg += ": " + err;
                }
                throw new RuntimeException(msg);
            }
            return MAPPER.readTree(out);
        }
    }

    public static JsonNode probeStream(InputStream inputStream) throws Exception {
        if (inputStream == null) {
            throw new IllegalArgumentException("inputStream is required");
        }

        String ffprobe = System.getProperty("ffprobe.path");
        if (ffprobe == null || ffprobe.isBlank()) ffprobe = "ffprobe";

        ProcessBuilder pb = new ProcessBuilder(
                ffprobe,
                "-v", "error",
                "-select_streams", "a",
            "-show_entries", "stream=index,codec_name,channels:format=duration",
                "-of", "json",
                "-i", "pipe:0"
        );

        Process p = pb.start();
        ExecutorService pumpExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch pumpDone = new CountDownLatch(1);
        pumpExecutor.submit(() -> {
            try (InputStream in = inputStream; OutputStream procIn = p.getOutputStream()) {
                in.transferTo(procIn);
            } catch (Exception ignored) {
            } finally {
                pumpDone.countDown();
            }
        });

        try (BufferedReader outReader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
             BufferedReader errReader = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String out = outReader.lines().collect(Collectors.joining("\n"));
            String err = errReader.lines().collect(Collectors.joining("\n"));
            int rc = p.waitFor();
            pumpDone.await(5, TimeUnit.SECONDS);
            if (rc != 0) {
                String msg = "ffprobe failed (rc=" + rc + ")";
                if (!err.isBlank()) {
                    msg += ": " + err;
                }
                throw new RuntimeException(msg);
            }
            return MAPPER.readTree(out);
        } finally {
            pumpExecutor.shutdownNow();
        }
    }
}
