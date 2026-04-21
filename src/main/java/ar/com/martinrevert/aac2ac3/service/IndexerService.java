package ar.com.martinrevert.aac2ac3.service;

import ar.com.martinrevert.aac2ac3.infra.JobRepository;
import ar.com.martinrevert.aac2ac3.model.Job;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class IndexerService {
    private static final Logger log = LoggerFactory.getLogger(IndexerService.class);
    private static final Pattern AAC2AC3_BACKUP_SUFFIX_PATTERN = Pattern.compile("\\.aac2ac3-backup-\\d+(?=\\.(mkv|mp4)$)", Pattern.CASE_INSENSITIVE);

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private ProbeService probeService;

    @Autowired
    private ar.com.martinrevert.aac2ac3.service.JobEventService jobEventService;

    @Autowired
    private ScanPathSettingsService scanPathSettingsService;

    @Autowired
    private SambaService sambaService;

    /**
     * Recursively scan `scanPath` for .mkv/.mp4 files and create a PENDING job
     * for files that contain at least one AAC audio stream and are not already
     * present in the job index (or are not already DONE/PENDING/RUNNING).
     */
    public void index() {
        indexSince(0L);
    }

    /**
     * Incremental index: only visits directories and files changed since {@code sinceMs}.
     * Directories whose last-modified time is <= sinceMs will be skipped to avoid
     * unnecessary IO. If {@code sinceMs} is 0, this performs a full scan.
     */
    public void indexSince(long sinceMs) {
        String scanPath = scanPathSettingsService.getScanPath();
        if (scanPath != null && scanPath.toLowerCase().startsWith("smb://")) {
            indexSamba(scanPath);
            return;
        }

        Path root = Path.of(scanPath);
        if (!Files.exists(root)) return;

        AtomicBoolean stop = new AtomicBoolean(false);
        final Path WORK_DIR = Path.of("work").toAbsolutePath().normalize();
        final Path LOG_DIR = Path.of("logs").toAbsolutePath().normalize();

        try {
            final java.util.concurrent.ConcurrentHashMap<String, Object> pathLocks = new java.util.concurrent.ConcurrentHashMap<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (stop.get()) return FileVisitResult.TERMINATE;
                    try {
                        Path abs = dir.toAbsolutePath().normalize();
                        if (abs.startsWith(WORK_DIR) || abs.startsWith(LOG_DIR)) return FileVisitResult.SKIP_SUBTREE;
                    } catch (Exception ignored) {}
                    try {
                        long dirMtime = attrs.lastModifiedTime().toMillis();
                        if (sinceMs > 0 && dirMtime <= sinceMs) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    } catch (Exception ignored) {}
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (stop.get()) return FileVisitResult.TERMINATE;
                    try {
                        if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                        String s = file.toString().toLowerCase();
                        if (!isMediaCandidate(s)) return FileVisitResult.CONTINUE;
                        if (sinceMs > 0 && attrs.lastModifiedTime().toMillis() <= sinceMs) return FileVisitResult.CONTINUE;

                        String abs = jobEventService.canonicalizePath(file);
                        String filenameLower = file.getFileName().toString().toLowerCase();
                        // ignore temporary/backup files produced by the worker
                        if (isTemporaryOrBackupFile(filenameLower)) return FileVisitResult.CONTINUE;
                        Optional<Job> existing = jobRepository.findByFilePath(abs);
                        if (existing.isPresent()) {
                            String st = existing.get().getStatus();
                            if ("DONE".equals(st) || "PENDING".equals(st) || "RUNNING".equals(st)) return FileVisitResult.CONTINUE;
                        }

                        JsonNode probe = probeService.probe(file.toFile());
                        JsonNode streams = probe.path("streams");
                        if (!streams.isArray() || streams.size() == 0) return FileVisitResult.CONTINUE;
                        boolean hasAac = false;
                        for (JsonNode stn : streams) {
                            String codec = stn.path("codec_name").asText("");
                            if ("aac".equalsIgnoreCase(codec)) { hasAac = true; break; }
                        }
                        if (hasAac) {
                            Object lock = jobEventService.pathLocks.computeIfAbsent(abs, k -> new Object());
                            try {
                                synchronized (lock) {
                                    Optional<Job> existing2 = jobRepository.findByFilePath(abs);
                                    if (existing2.isPresent()) {
                                        String st = existing2.get().getStatus();
                                        if ("DONE".equals(st) || "PENDING".equals(st) || "RUNNING".equals(st)) {
                                            return FileVisitResult.CONTINUE;
                                        }
                                    }
                                    Job j = new Job();
                                    j.setFilePath(abs);
                                    j.setStatus("PENDING");
                                    j.setCreatedAt(System.currentTimeMillis());
                                    Job saved;
                                    try {
                                        saved = jobRepository.save(j);
                                    } catch (org.springframework.dao.DataIntegrityViolationException | jakarta.persistence.PersistenceException ex) {
                                        var existingJob = jobRepository.findByFilePath(abs);
                                        if (existingJob.isPresent()) {
                                            saved = existingJob.get();
                                        } else {
                                            throw ex;
                                        }
                                    }
                                    try { jobEventService.publishJob(saved); } catch (Exception ignored) {}
                                }
                            } finally {
                                jobEventService.pathLocks.remove(abs);
                            }
                        }
                    } catch (Exception e) {
                        // probe may fail; skip this file
                        log.warn("Failed to inspect candidate file {}", file, e);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Indexer walk failed for scanPath [{}]", scanPath, e);
        }
    }

    private void indexSamba(String scanPath) {
        try {
            ScanPathSettingsService.SambaConfig cfg = scanPathSettingsService.getSambaConfig();
            if (!cfg.isConfigured() || !cfg.hasCredentials()) {
                log.warn("SMB scanPath configured but SMB credentials are missing; skipping index");
                return;
            }

            long startMs = System.currentTimeMillis();
            int totalCandidates = 0;
            int skippedExisting = 0;
            int probeFailures = 0;
            int noAac = 0;
            int enqueued = 0;

            var smbCandidates = sambaService.listMediaRecursively(scanPath, cfg);
            totalCandidates = smbCandidates.size();
            log.info("SMB index discovered {} media candidates under [{}]", totalCandidates, scanPath);

            for (String smbFileUri : smbCandidates) {
                try {
                    if (isSmbTemporaryOrBackupCandidate(smbFileUri)) {
                        continue;
                    }

                    Optional<Job> existing = jobRepository.findByFilePath(smbFileUri);
                    if (existing.isPresent()) {
                        String st = existing.get().getStatus();
                        if ("DONE".equals(st) || "PENDING".equals(st) || "RUNNING".equals(st)) {
                            skippedExisting++;
                            continue;
                        }
                    }

                    JsonNode probe = sambaService.withFileInputStream(smbFileUri, cfg, probeService::probeStreamAudioOnly);
                    boolean hasAac = containsAacAudio(probe);
                    // Some container layouts are not reliably detected from non-seekable stdin probing.
                    // Fall back to ffprobe over authenticated SMB URI before declaring no AAC.
                    if (!hasAac) {
                        String authenticatedUri = sambaService.toAuthenticatedSmbUri(smbFileUri, cfg);
                        JsonNode fallbackProbe = probeService.probePath(authenticatedUri);
                        hasAac = containsAacAudio(fallbackProbe);
                    }
                    if (!hasAac) {
                        noAac++;
                        continue;
                    }

                    Object lock = jobEventService.pathLocks.computeIfAbsent(smbFileUri, k -> new Object());
                    try {
                        synchronized (lock) {
                            Optional<Job> existing2 = jobRepository.findByFilePath(smbFileUri);
                            if (existing2.isPresent()) {
                                String st = existing2.get().getStatus();
                                if ("DONE".equals(st) || "PENDING".equals(st) || "RUNNING".equals(st)) {
                                    continue;
                                }
                            }

                            Job j = new Job();
                            j.setFilePath(smbFileUri);
                            j.setStatus("PENDING");
                            j.setCreatedAt(System.currentTimeMillis());
                            Job saved;
                            try {
                                saved = jobRepository.save(j);
                            } catch (org.springframework.dao.DataIntegrityViolationException | jakarta.persistence.PersistenceException ex) {
                                var existingJob = jobRepository.findByFilePath(smbFileUri);
                                if (existingJob.isPresent()) {
                                    saved = existingJob.get();
                                } else {
                                    throw ex;
                                }
                            }
                            try {
                                jobEventService.publishJob(saved);
                            } catch (Exception ignored) {}
                            enqueued++;
                        }
                    } finally {
                        jobEventService.pathLocks.remove(smbFileUri);
                    }
                } catch (Exception ex) {
                    probeFailures++;
                    log.warn("Failed to inspect SMB candidate file {}", smbFileUri, ex);
                }
            }

            long elapsed = System.currentTimeMillis() - startMs;
            log.info("SMB index summary: candidates={} skippedExisting={} noAac={} probeFailures={} enqueued={} elapsedMs={}",
                    totalCandidates,
                    skippedExisting,
                    noAac,
                    probeFailures,
                    enqueued,
                    elapsed);
        } catch (Exception e) {
            log.error("SMB index failed for scanPath [{}]", scanPath, e);
        }
    }

    private static boolean isMediaCandidate(String pathLower) {
        return pathLower.endsWith(".mkv") || pathLower.endsWith(".mp4");
    }

    private static boolean isTemporaryOrBackupFile(String filenameLower) {
        if (filenameLower.endsWith(".tmp.mkv") || filenameLower.endsWith(".tmp.mp4")) return true;
        if (filenameLower.endsWith(".bak.mkv") || filenameLower.endsWith(".bak.mp4")) return true;
        if (AAC2AC3_BACKUP_SUFFIX_PATTERN.matcher(filenameLower).find()) return true;
        return filenameLower.startsWith("job-") && (filenameLower.contains("-tmp") || filenameLower.contains("-backup"));
    }

    private static boolean isSmbTemporaryOrBackupCandidate(String smbUri) {
        if (smbUri == null || smbUri.isBlank()) return false;
        int slash = smbUri.lastIndexOf('/');
        String name = slash >= 0 ? smbUri.substring(slash + 1) : smbUri;
        return isTemporaryOrBackupFile(name.toLowerCase());
    }

    private static boolean containsAacAudio(JsonNode probe) {
        JsonNode streams = probe.path("streams");
        if (!streams.isArray() || streams.size() == 0) {
            return false;
        }
        for (JsonNode stn : streams) {
            String codec = stn.path("codec_name").asText("");
            if ("aac".equalsIgnoreCase(codec)) {
                return true;
            }
        }
        return false;
    }
}
