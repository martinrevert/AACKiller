package ar.com.martinrevert.aac2ac3.service;

import ar.com.martinrevert.aac2ac3.infra.JobRepository;
import ar.com.martinrevert.aac2ac3.model.Job;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.util.Optional;
import java.util.concurrent.*;

@Service
public class DirectoryWatcherService {
    private static final Logger log = LoggerFactory.getLogger(DirectoryWatcherService.class);

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

    @Value("${index.watchEnabled:true}")
    private boolean watchEnabled;

    @Value("${index.watch.threads:2}")
    private int watchThreads;

    @Value("${index.watch.probeRetries:3}")
    private int probeRetries;

    @Value("${index.watch.probeRetryDelayMs:2000}")
    private int probeRetryDelayMs;

    private WatchService watchService;
    private final ConcurrentMap<WatchKey, Path> keys = new ConcurrentHashMap<>();
    private volatile boolean running = false;
    private Thread watcherThread;
    private ExecutorService taskExecutor;
    private ScheduledExecutorService scheduledExecutor;
    private static final java.nio.file.Path WORK_DIR = java.nio.file.Path.of("work").toAbsolutePath().normalize();
    private static final java.nio.file.Path LOG_DIR = java.nio.file.Path.of("logs").toAbsolutePath().normalize();

    public boolean isRunning() { return running; }

    public synchronized void start() {
        if (!watchEnabled) return;
        if (running) return;

        try {
            String scanPath = scanPathSettingsService.getScanPath();
            if (scanPath != null && scanPath.toLowerCase().startsWith("smb://")) {
                log.info("Directory watcher is disabled for SMB scan paths; indexing runs on worker start");
                return;
            }
            Path root = Path.of(scanPath);
            if (!Files.exists(root)) Files.createDirectories(root);
            watchService = FileSystems.getDefault().newWatchService();
            registerAll(root);

            taskExecutor = Executors.newFixedThreadPool(Math.max(1, watchThreads));
            scheduledExecutor = Executors.newScheduledThreadPool(1);

            running = true;
            watcherThread = new Thread(this::processLoop, "directory-watcher");
            watcherThread.setDaemon(true);
            watcherThread.start();
            log.info("DirectoryWatcherService started watching: {}", root.toAbsolutePath());
        } catch (IOException e) {
            log.error("DirectoryWatcherService failed to start", e);
        }
    }

    public synchronized void stop() {
        running = false;
        try { if (watchService != null) watchService.close(); } catch (Exception ignored) {}
        try { if (taskExecutor != null) taskExecutor.shutdownNow(); } catch (Exception ignored) {}
        try { if (scheduledExecutor != null) scheduledExecutor.shutdownNow(); } catch (Exception ignored) {}
        if (watcherThread != null) watcherThread.interrupt();
        log.info("DirectoryWatcherService stopped");
    }

    private void registerAll(Path start) throws IOException {
        try (var s = Files.walk(start)) {
            s.filter(Files::isDirectory).forEach(this::registerDir);
        }
    }

    private void registerDir(Path dir) {
        try {
            Path abs = dir.toAbsolutePath().normalize();
            if (abs.startsWith(WORK_DIR) || abs.startsWith(LOG_DIR)) return;
            WatchKey key = dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            keys.put(key, dir);
        } catch (IOException e) {
            log.warn("Failed to register directory for watching: {}", dir, e);
        }
    }

    @SuppressWarnings("unchecked")
    private void processLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take(); // blocking, non-polling
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException cwse) {
                break;
            }

            Path dir = keys.get(key);
            if (dir == null) { key.reset(); continue; }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) continue;
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path name = ev.context();
                Path child = dir.resolve(name);

                log.debug("watch-event: {} -> {}", kind.name(), child.toAbsolutePath());

                if (Files.isDirectory(child) && kind == StandardWatchEventKinds.ENTRY_CREATE) {
                    try { registerAll(child); } catch (IOException ignored) {}
                    continue;
                }

                String lower = child.toString().toLowerCase();
                if (lower.endsWith(".mkv") && (kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY)) {
                    submitProbeTask(child, 0);
                }
            }

            boolean valid = key.reset();
            if (!valid) keys.remove(key);
        }
    }

    private void submitProbeTask(Path file, int attempt) {
        if (taskExecutor == null) return;
        taskExecutor.submit(() -> {
            try {
                String absPathStr = jobEventService.canonicalizePath(file);
                Path absPath = Path.of(absPathStr);
                // skip work/logs dir files
                if (absPath.startsWith(WORK_DIR) || absPath.startsWith(LOG_DIR)) return;
                String filenameLower = file.getFileName().toString().toLowerCase();
                // skip temporary/backup files produced by the worker
                if (filenameLower.endsWith(".tmp.mkv") || filenameLower.endsWith(".bak.mkv") || (filenameLower.startsWith("job-") && (filenameLower.contains("-tmp") || filenameLower.contains("-backup")))) return;

                JsonNode probe = probeService.probe(file.toFile());
                var streams = probe.path("streams");
                if (!streams.isArray() || streams.size() == 0) return;
                boolean hasAac = false;
                for (JsonNode st : streams) {
                    String codec = st.path("codec_name").asText("");
                    if ("aac".equalsIgnoreCase(codec)) { hasAac = true; break; }
                }
                if (!hasAac) return;

                String abs = absPathStr;
                // Use a shared per-path lock to avoid races where multiple threads create the same job
                Object lock = jobEventService.pathLocks.computeIfAbsent(abs, k -> new Object());
                try {
                    synchronized (lock) {
                        Optional<Job> existing = jobRepository.findByFilePath(abs);
                        if (existing.isPresent()) {
                            String s = existing.get().getStatus();
                            if ("DONE".equals(s) || "PENDING".equals(s) || "RUNNING".equals(s)) {
                                return;
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
                            // another thread/process inserted the same path concurrently; load existing
                            var existingJob = jobRepository.findByFilePath(abs);
                            if (existingJob.isPresent()) {
                                saved = existingJob.get();
                            } else {
                                throw ex;
                            }
                        }
                        try { jobEventService.publishJob(saved); } catch (Exception ignored) {}
                        log.info("DirectoryWatcherService enqueued job: {}", abs);
                    }
                } finally {
                    jobEventService.pathLocks.remove(abs, lock);
                }
            } catch (Exception e) {
                if (attempt < probeRetries) {
                    if (scheduledExecutor != null) {
                        scheduledExecutor.schedule(() -> submitProbeTask(file, attempt + 1), probeRetryDelayMs, TimeUnit.MILLISECONDS);
                    }
                } else {
                    log.warn("DirectoryWatcherService: probe failed for {}", file, e);
                }
            }
        });
    }

    @PreDestroy
    public void shutdown() { stop(); }
}
