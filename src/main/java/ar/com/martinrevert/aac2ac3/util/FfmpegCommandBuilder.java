package ar.com.martinrevert.aac2ac3.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class FfmpegCommandBuilder {
    private FfmpegCommandBuilder() {}

    /**
     * Build a simple ffmpeg command that copies video/subs and converts audio to AC3.
     * This is a conservative default; later we will build per-stream mappings.
     */
    public static List<String> buildBasicConvert(File input, File output, int threads) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-hide_banner");
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(input.getAbsolutePath());
        cmd.add("-map"); cmd.add("0");
        cmd.add("-c:v"); cmd.add("copy");
        cmd.add("-c:s"); cmd.add("copy");
        // convert all audio to ac3 (simple default)
        cmd.add("-c:a"); cmd.add("ac3");
        cmd.add("-b:a"); cmd.add("192k");
        cmd.add("-threads"); cmd.add(String.valueOf(threads));
        cmd.add(output.getAbsolutePath());
        return cmd;
    }

    /**
     * Build a per-audio-stream mapping based on ffprobe JSON. Only convert streams
     * that are AAC to AC3; copy other audio streams.
     * The provided probe JSON should include only audio streams (probe called with -select_streams a).
     */
    public static List<String> buildFromProbe(JsonNode probe, File input, File output, int threads) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-hide_banner");
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(input.getAbsolutePath());
        cmd.add("-map"); cmd.add("0");
        cmd.add("-c:v"); cmd.add("copy");
        cmd.add("-c:s"); cmd.add("copy");

        JsonNode streams = probe.path("streams");
        if (!streams.isArray() || streams.size() == 0) {
            // fallback: convert all audio to ac3
            cmd.add("-c:a"); cmd.add("ac3");
            cmd.add("-b:a"); cmd.add("192k");
        } else {
            // set per-audio mapping by audio stream order
            for (int i = 0; i < streams.size(); i++) {
                JsonNode s = streams.get(i);
                String codec = s.path("codec_name").asText("");
                int channels = s.path("channels").asInt(2);
                if ("aac".equalsIgnoreCase(codec)) {
                    cmd.add("-c:a:" + i);
                    cmd.add("ac3");
                    String bitrate = channels >= 4 ? "640k" : "192k";
                    cmd.add("-b:a:" + i);
                    cmd.add(bitrate);
                } else {
                    cmd.add("-c:a:" + i);
                    cmd.add("copy");
                }
            }
        }

        cmd.add("-threads"); cmd.add(String.valueOf(threads));
        cmd.add(output.getAbsolutePath());
        return cmd;
    }
}
