package ar.com.martinrevert.aac2ac3.service;

import ar.com.martinrevert.aac2ac3.infra.JobRepository;
import ar.com.martinrevert.aac2ac3.model.Job;
import ar.com.martinrevert.aac2ac3.util.FfmpegCommandBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class WorkerService {
    private static final Logger log = LoggerFactory.getLogger(WorkerService.class);

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private IndexerService indexerService;

    @Autowired
    private DirectoryWatcherService directoryWatcherService;

    @Autowired
    private ProbeService probeService;

    @Autowired
    private FfmpegService ffmpegService;

    @Autowired
    private SambaService sambaService;

    @Autowired
    private ScanPathSettingsService scanPathSettingsService;

    @Autowired
    private ar.com.martinrevert.aac2ac3.service.JobEventService jobEventService;

    @Value("${worker.maxConcurrency:2}")
    private int maxConcurrency;

    @Value("${ffmpeg.timeout.seconds:21600}")
    private int ffmpegTimeoutSeconds;
    @Value("${ffmpeg.threads:0}")
    private int ffmpegThreads;

    private final Object lock = new Object();
    private volatile boolean running = false;
    private ExecutorService poller;
    private ExecutorService workerPool;

    public void start() {
        synchronized (lock) {
            if (running) return;
            running = true;
            // Build the index first (probe files) before starting workers
            try { indexerService.index(); } catch (Exception e) { log.error("Initial index run failed", e); }
            poller = Executors.newSingleThreadExecutor();
            workerPool = Executors.newFixedThreadPool(Math.max(1, maxConcurrency));
            poller.submit(this::loop);
            // start directory watcher to auto-enqueue new .mkv/.mp4 files
            try { directoryWatcherService.start(); } catch (Exception ignored) {}
            try { jobEventService.publishWorkerStatus("running"); } catch (Exception ignored) {}
        }
    }

    public void stop() {
        synchronized (lock) {
            running = false;
            try { directoryWatcherService.stop(); } catch (Exception ignored) {}
            if (poller != null) poller.shutdownNow();
            if (workerPool != null) workerPool.shutdown();
            try { jobEventService.publishWorkerStatus("stopped"); } catch (Exception ignored) {}
        }
    }

    public boolean isRunning() { return running; }

    private void loop() {
        while (running) {
            try {
                List<Job> pending = jobRepository.findByStatus("PENDING");
                for (Job j : pending) {
                    j.setStatus("RUNNING");
                    j.setStartedAt(System.currentTimeMillis());
                    Job saved = jobRepository.save(j);
                    try { jobEventService.publishJob(saved); } catch (Exception ignored) {}
                    workerPool.submit(() -> processJob(saved));
                }
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Worker polling loop failed", e);
            }
        }
    }

    void processJob(Job job) {
        if (job.getFilePath() != null && job.getFilePath().toLowerCase().startsWith("smb://")) {
            processSmbJob(job);
            return;
        }

        Path input = Path.of(job.getFilePath());
        try {
            if (!Files.exists(input)) {
                job.setStatus("FAILED");
                job.setLogsPath("stdout:file-not-found");
                log.warn("Job {} failed: source file not found [{}]", job.getId(), job.getFilePath());
                Job saved = jobRepository.save(job);
                try { jobEventService.publishJob(saved); } catch (Exception ignored) {}
                return;
            }
            Path parent = input.getParent() == null ? Path.of(".") : input.getParent();
            String name = input.getFileName().toString();

            // Use a dedicated work directory (outside of typical scan paths) for backups and tmp outputs
            Path workDir = Path.of("work");
            try { Files.createDirectories(workDir); } catch (Exception ignored) {}

            String inputExt = extensionForPath(name);
            Path backup = workDir.resolve("job-" + job.getId() + "-backup" + inputExt);
            // move original to backup location (into work dir)
            try {
                Files.move(input, backup, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ex) {
                // fallback to non-atomic
                Files.move(input, backup, StandardCopyOption.REPLACE_EXISTING);
            }

            // probe backup for audio streams
            JsonNode probe = probeService.probe(backup.toFile());

            Path tmpOut = workDir.resolve("job-" + job.getId() + "-tmp" + inputExt);

            int threadsToUse = ffmpegThreads > 0 ? ffmpegThreads : Math.max(1, maxConcurrency);
            List<String> cmd = FfmpegCommandBuilder.buildFromProbe(probe, backup.toFile(), tmpOut.toFile(), threadsToUse);
            log.info("Job {} starting ffmpeg conversion for [{}]", job.getId(), job.getFilePath());

            int rc = ffmpegService.run(cmd, parent.toFile(), Duration.ofSeconds(ffmpegTimeoutSeconds));
            if (rc != 0) {
                // restore backup
                Files.move(backup, input, StandardCopyOption.REPLACE_EXISTING);
                job.setStatus("FAILED");
                job.setFinishedAt(System.currentTimeMillis());
                job.setLogsPath("stdout:ffmpeg-failed");
                log.warn("Job {} failed: ffmpeg exit code {} for [{}]", job.getId(), rc, job.getFilePath());
                Job saved = jobRepository.save(job);
                try { jobEventService.publishJob(saved); } catch (Exception ignored) {}
                return;
            }

            // verify output codecs
            JsonNode outProbe = probeService.probe(tmpOut.toFile());
            boolean ok = verifyConversion(probe, outProbe);
            if (!ok) {
                // restore
                Files.move(backup, input, StandardCopyOption.REPLACE_EXISTING);
                job.setStatus("FAILED");
                job.setFinishedAt(System.currentTimeMillis());
                job.setLogsPath("stdout:verification-failed");
                log.warn("Job {} failed: output verification did not pass for [{}]", job.getId(), job.getFilePath());
                Job saved = jobRepository.save(job);
                try { jobEventService.publishJob(saved); } catch (Exception ignored) {}
                return;
            }

            // move tmpOut -> original name
            Files.move(tmpOut, parent.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            // optionally remove backup
            try { Files.deleteIfExists(backup); } catch (Exception ignored) {}
            job.setStatus("DONE");
            job.setFinishedAt(System.currentTimeMillis());
            job.setLogsPath("stdout");
            log.info("Job {} completed successfully for [{}]", job.getId(), job.getFilePath());
            Job saved = jobRepository.save(job);
            try { jobEventService.publishJob(saved); } catch (Exception ignored) {}
        } catch (Exception e) {
            try { Files.move(Path.of(job.getFilePath() + ""), Path.of(job.getFilePath())); } catch (Exception ignored) {}
            job.setStatus("FAILED");
            job.setFinishedAt(System.currentTimeMillis());
            job.setLogsPath("stdout:exception");
            log.error("Job {} failed with exception for [{}]", job.getId(), job.getFilePath(), e);
            Job savedEx = jobRepository.save(job);
            try { jobEventService.publishJob(savedEx); } catch (Exception ignored) {}
        }
    }

    private void processSmbJob(Job job) {
        String smbUri = job.getFilePath();
        String smbExt = extensionForPath(smbUri);
        Path workDir = Path.of("work");
        Path localBackup = workDir.resolve("job-" + job.getId() + "-smb-backup" + smbExt);
        Path localOut = workDir.resolve("job-" + job.getId() + "-smb-out" + smbExt);
        String backupSmbUri = null;
        boolean renamedToBackup = false;

        try {
            ScanPathSettingsService.SambaConfig cfg = scanPathSettingsService.getSambaConfig();
            if (!cfg.isConfigured() || !cfg.hasCredentials()) {
                job.setStatus("FAILED");
                job.setFinishedAt(System.currentTimeMillis());
                job.setLogsPath("stdout:smb-credentials-missing");
                log.warn("Job {} failed: SMB path configured but credentials are missing", job.getId());
                Job saved = jobRepository.save(job);
                try { jobEventService.publishJob(saved); } catch (Exception ignored) {}
                return;
            }

            if (!sambaService.exists(smbUri, cfg)) {
                job.setStatus("FAILED");
                job.setLogsPath("stdout:file-not-found");
                log.warn("Job {} failed: SMB source file not found [{}]", job.getId(), smbUri);
                Job saved = jobRepository.save(job);
                try { jobEventService.publishJob(saved); } catch (Exception ignored) {}
                return;
            }

            Files.createDirectories(workDir);
            try { Files.deleteIfExists(localBackup); } catch (Exception ignored) {}
            try { Files.deleteIfExists(localOut); } catch (Exception ignored) {}

            backupSmbUri = sambaService.withSuffixBeforeExtension(smbUri, ".aac2ac3-backup-" + job.getId());
            sambaService.rename(smbUri, backupSmbUri, cfg);
            renamedToBackup = true;

            // Keep SMB for source/target storage but run ffmpeg on local disk for reliability.
            sambaService.downloadToLocal(backupSmbUri, cfg, localBackup);
            JsonNode probe = probeService.probe(localBackup.toFile());

            int threadsToUse = ffmpegThreads > 0 ? ffmpegThreads : Math.max(1, maxConcurrency);
            List<String> cmd = FfmpegCommandBuilder.buildFromProbe(probe, localBackup.toFile(), localOut.toFile(), threadsToUse);
            log.info("Job {} starting staged SMB conversion for [{}]", job.getId(), smbUri);

            int rc = ffmpegService.run(cmd, workDir.toFile(), Duration.ofSeconds(ffmpegTimeoutSeconds));
            if (rc != 0) {
                sambaService.rename(backupSmbUri, smbUri, cfg);
                job.setStatus("FAILED");
                job.setFinishedAt(System.currentTimeMillis());
                job.setLogsPath("stdout:ffmpeg-failed");
                log.warn("Job {} failed: staged ffmpeg exit code {} for [{}]", job.getId(), rc, smbUri);
                Job saved = jobRepository.save(job);
                try { jobEventService.publishJob(saved); } catch (Exception ignored) {}
                return;
            }

            JsonNode outProbe = probeService.probe(localOut.toFile());
            boolean ok = verifyConversion(probe, outProbe);
            if (!ok) {
                sambaService.rename(backupSmbUri, smbUri, cfg);
                job.setStatus("FAILED");
                job.setFinishedAt(System.currentTimeMillis());
                job.setLogsPath("stdout:verification-failed");
                log.warn("Job {} failed: staged output verification did not pass for [{}]", job.getId(), smbUri);
                Job saved = jobRepository.save(job);
                try { jobEventService.publishJob(saved); } catch (Exception ignored) {}
                return;
            }

            sambaService.uploadFromLocal(localOut, smbUri, cfg);

            JsonNode uploadedProbe = sambaService.withFileInputStream(smbUri, cfg, probeService::probeStream);
            if (!verifyConversion(probe, uploadedProbe)) {
                sambaService.rename(backupSmbUri, smbUri, cfg);
                job.setStatus("FAILED");
                job.setFinishedAt(System.currentTimeMillis());
                job.setLogsPath("stdout:verification-failed");
                log.warn("Job {} failed: uploaded SMB output verification did not pass for [{}]", job.getId(), smbUri);
                Job saved = jobRepository.save(job);
                try { jobEventService.publishJob(saved); } catch (Exception ignored) {}
                return;
            }

            try { sambaService.delete(backupSmbUri, cfg); } catch (Exception ignored) {}
            renamedToBackup = false;

            job.setStatus("DONE");
            job.setFinishedAt(System.currentTimeMillis());
            job.setLogsPath("stdout");
            log.info("Job {} completed successfully for staged SMB path [{}]", job.getId(), smbUri);
            Job saved = jobRepository.save(job);
            try { jobEventService.publishJob(saved); } catch (Exception ignored) {}
        } catch (Exception e) {
            if (renamedToBackup && backupSmbUri != null) {
                try {
                    ScanPathSettingsService.SambaConfig cfg = scanPathSettingsService.getSambaConfig();
                    sambaService.rename(backupSmbUri, smbUri, cfg);
                } catch (Exception restoreEx) {
                    log.error("Job {} could not restore SMB backup [{}] -> [{}]", job.getId(), backupSmbUri, smbUri, restoreEx);
                }
            }
            job.setStatus("FAILED");
            job.setFinishedAt(System.currentTimeMillis());
            job.setLogsPath("stdout:exception");
            log.error("Job {} failed with SMB exception for [{}]", job.getId(), smbUri, e);
            Job savedEx = jobRepository.save(job);
            try { jobEventService.publishJob(savedEx); } catch (Exception ignored) {}
        } finally {
            try { Files.deleteIfExists(localBackup); } catch (Exception ignored) {}
            try { Files.deleteIfExists(localOut); } catch (Exception ignored) {}
        }
    }

    private boolean verifyConversion(JsonNode before, JsonNode after) {
        // before/after only have audio streams (we probed with -select_streams a)
        try {
            var bStreams = before.path("streams");
            var aStreams = after.path("streams");
            if (!bStreams.isArray() || !aStreams.isArray()) return false;
            if (aStreams.size() < bStreams.size()) return false;

            double beforeDuration = before.path("format").path("duration").asDouble(0d);
            double afterDuration = after.path("format").path("duration").asDouble(0d);
            // Guard against truncated outputs: output should be roughly same length as input.
            if (beforeDuration > 1d && afterDuration > 0d) {
                double ratio = afterDuration / beforeDuration;
                if (ratio < 0.95d) return false;
            }

            int n = Math.min(bStreams.size(), aStreams.size());
            for (int i = 0; i < n; i++) {
                String beforeCodec = bStreams.get(i).path("codec_name").asText("");
                String afterCodec = aStreams.get(i).path("codec_name").asText("");
                if ("aac".equalsIgnoreCase(beforeCodec)) {
                    if (!"ac3".equalsIgnoreCase(afterCodec)) return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @PreDestroy
    public void shutdown() {
        stop();
    }

    private static String extensionForPath(String path) {
        String lower = path == null ? "" : path.toLowerCase();
        if (lower.endsWith(".mp4")) return ".mp4";
        return ".mkv";
    }
}
