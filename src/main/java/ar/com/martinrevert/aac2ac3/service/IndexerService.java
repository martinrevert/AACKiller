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
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class IndexerService {
    private static final Logger log = LoggerFactory.getLogger(IndexerService.class);

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
     * Recursively scan `scanPath` for .mkv files and create a PENDING job
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
                        if (!s.endsWith(".mkv")) return FileVisitResult.CONTINUE;
                        if (sinceMs > 0 && attrs.lastModifiedTime().toMillis() <= sinceMs) return FileVisitResult.CONTINUE;

                        String abs = jobEventService.canonicalizePath(file);
                        String filenameLower = file.getFileName().toString().toLowerCase();
                        // ignore temporary/backup files produced by the worker
                        if (filenameLower.endsWith(".tmp.mkv") || filenameLower.endsWith(".bak.mkv")) return FileVisitResult.CONTINUE;
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

            for (String smbFileUri : sambaService.listMkvRecursively(scanPath, cfg)) {
                try {
                    Optional<Job> existing = jobRepository.findByFilePath(smbFileUri);
                    if (existing.isPresent()) {
                        String st = existing.get().getStatus();
                        if ("DONE".equals(st) || "PENDING".equals(st) || "RUNNING".equals(st)) continue;
                    }

                    JsonNode probe = sambaService.withFileInputStream(smbFileUri, cfg, probeService::probeStream);
                    JsonNode streams = probe.path("streams");
                    if (!streams.isArray() || streams.size() == 0) continue;
                    boolean hasAac = false;
                    for (JsonNode stn : streams) {
                        String codec = stn.path("codec_name").asText("");
                        if ("aac".equalsIgnoreCase(codec)) {
                            hasAac = true;
                            break;
                        }
                    }
                    if (!hasAac) continue;

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
                        }
                    } finally {
                        jobEventService.pathLocks.remove(smbFileUri);
                    }
                } catch (Exception ex) {
                    log.warn("Failed to inspect SMB candidate file {}", smbFileUri, ex);
                }
            }
        } catch (Exception e) {
            log.error("SMB index failed for scanPath [{}]", scanPath, e);
        }
    }
}
