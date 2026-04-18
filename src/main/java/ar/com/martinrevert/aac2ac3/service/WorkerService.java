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
    private ar.com.martinrevert.aac2ac3.service.JobEventService jobEventService;

    @Value("${worker.maxConcurrency:2}")
    private int maxConcurrency;

    @Value("${ffmpeg.timeout.seconds:7200}")
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
            // start directory watcher to auto-enqueue new .mkv files
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

            Path backup = workDir.resolve("job-" + job.getId() + "-backup.mkv");
            // move original to backup location (into work dir)
            try {
                Files.move(input, backup, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ex) {
                // fallback to non-atomic
                Files.move(input, backup, StandardCopyOption.REPLACE_EXISTING);
            }

            // probe backup for audio streams
            JsonNode probe = probeService.probe(backup.toFile());

            Path tmpOut = workDir.resolve("job-" + job.getId() + "-tmp.mkv");

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

    private boolean verifyConversion(JsonNode before, JsonNode after) {
        // before/after only have audio streams (we probed with -select_streams a)
        try {
            var bStreams = before.path("streams");
            var aStreams = after.path("streams");
            if (!bStreams.isArray() || !aStreams.isArray()) return true; // nothing to verify
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
}
