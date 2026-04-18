package ar.com.martinrevert.aac2ac3.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public final class ProbeUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProbeUtil() {}

    /**
     * Run ffprobe to get audio stream info as JsonNode.
     */
    public static JsonNode probe(File file) throws Exception {
        if (!file.exists()) throw new IllegalArgumentException("file not found: " + file);

        // allow overriding ffprobe executable path for testing or env differences
        String ffprobe = System.getProperty("ffprobe.path");
        if (ffprobe == null || ffprobe.isBlank()) ffprobe = "ffprobe";

        ProcessBuilder pb = new ProcessBuilder(
            ffprobe,
            "-v", "error",
            "-select_streams", "a",
            "-show_entries", "stream=index,codec_name,channels",
            "-of", "json",
            file.getAbsolutePath()
        );

        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String out = br.lines().collect(Collectors.joining("\n"));
            int rc = p.waitFor();
            if (rc != 0) {
                throw new RuntimeException("ffprobe failed (rc=" + rc + ")");
            }
            return MAPPER.readTree(out);
        }
    }
}
