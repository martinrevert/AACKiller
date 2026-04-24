package ar.com.martinrevert.aac2ac3.service;

import ar.com.martinrevert.aac2ac3.infra.ProbeIndexRepository;
import ar.com.martinrevert.aac2ac3.model.ProbeIndexEntry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

@Service
public class ProbeIndexService {
    public static final String STATUS_AAC_MATCH = "AAC_MATCH";
    public static final String STATUS_NO_AAC = "NO_AAC";
    public static final String STATUS_PROBE_ERROR = "PROBE_ERROR";
    public static final String CODEC_UNKNOWN = "unknown";

    @Autowired
    private ProbeIndexRepository probeIndexRepository;

    public Optional<ProbeIndexEntry> findReusableLocal(String filePath, long fileSize, long fileMtime) {
        Optional<ProbeIndexEntry> existing = probeIndexRepository.findByFilePath(filePath);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        ProbeIndexEntry entry = existing.get();
        if (!matchesLocalIdentity(entry, fileSize, fileMtime)) {
            return Optional.empty();
        }
        if (entry.getProbeStatus() == null || entry.getProbeStatus().isBlank()) {
            // Legacy entry: force re-probe for safety.
            return Optional.empty();
        }
        return existing;
    }

    public ProbeIndexEntry upsertLocal(String filePath,
                                       String probeStatus,
                                       String detectedCodec,
                                       String skipReason,
                                       long fileSize,
                                       long fileMtime) {
        ProbeIndexEntry entry = probeIndexRepository.findByFilePath(filePath).orElseGet(ProbeIndexEntry::new);
        entry.setFilePath(filePath);
        entry.setProbeStatus(probeStatus);
        entry.setDetectedCodec(normalizeCodec(detectedCodec));
        entry.setSkipReason(skipReason == null ? "" : skipReason);
        entry.setFileSize(fileSize);
        entry.setFileMtime(fileMtime);
        entry.setUpdatedAt(System.currentTimeMillis());
        return probeIndexRepository.save(entry);
    }

    public ProbeIndexEntry upsertSmb(String smbFilePath,
                                     String probeStatus,
                                     String detectedCodec,
                                     String skipReason) {
        ProbeIndexEntry entry = probeIndexRepository.findByFilePath(smbFilePath).orElseGet(ProbeIndexEntry::new);
        entry.setFilePath(smbFilePath);
        entry.setProbeStatus(probeStatus);
        entry.setDetectedCodec(normalizeCodec(detectedCodec));
        entry.setSkipReason(skipReason == null ? "" : skipReason);
        // SMB scan currently does not have a stable size+mtime identity source.
        entry.setFileSize(null);
        entry.setFileMtime(null);
        entry.setUpdatedAt(System.currentTimeMillis());
        return probeIndexRepository.save(entry);
    }

    public ProbeClassification classifyProbe(JsonNode probe) {
        JsonNode streams = probe.path("streams");
        if (!streams.isArray() || streams.size() == 0) {
            return new ProbeClassification(STATUS_NO_AAC, CODEC_UNKNOWN, "no-audio-streams", false);
        }

        String firstCodec = CODEC_UNKNOWN;
        for (JsonNode stream : streams) {
            String codec = normalizeCodec(stream.path("codec_name").asText(""));
            if (!CODEC_UNKNOWN.equals(codec) && CODEC_UNKNOWN.equals(firstCodec)) {
                firstCodec = codec;
            }
            if ("aac".equalsIgnoreCase(codec)) {
                return new ProbeClassification(STATUS_AAC_MATCH, "aac", "", true);
            }
        }

        return new ProbeClassification(STATUS_NO_AAC, firstCodec, "non-aac-codec", false);
    }

    static boolean matchesLocalIdentity(ProbeIndexEntry entry, long fileSize, long fileMtime) {
        if (entry.getFileSize() == null || entry.getFileMtime() == null) {
            return false;
        }
        return entry.getFileSize() == fileSize && entry.getFileMtime() == fileMtime;
    }

    static String normalizeCodec(String codec) {
        if (codec == null || codec.isBlank()) {
            return CODEC_UNKNOWN;
        }
        return codec.trim().toLowerCase(Locale.ROOT);
    }

    public record ProbeClassification(String probeStatus, String detectedCodec, String skipReason, boolean hasAac) {}
}
