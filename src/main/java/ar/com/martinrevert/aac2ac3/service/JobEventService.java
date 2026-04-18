package ar.com.martinrevert.aac2ac3.service;

import ar.com.martinrevert.aac2ac3.infra.JobRepository;
import ar.com.martinrevert.aac2ac3.model.Job;
import ar.com.martinrevert.aac2ac3.api.JobDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class JobEventService {
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    @Autowired
    private JobRepository jobRepository;

    // Shared per-path locks used by Indexer/Watcher/Controllers to avoid duplicate job creation
    public static final java.util.concurrent.ConcurrentHashMap<String, Object> pathLocks = new java.util.concurrent.ConcurrentHashMap<>();

    // current worker status exposed to new SSE subscribers
    private volatile String workerStatus = "stopped";

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError((e) -> emitters.remove(emitter));

        // send initial snapshot
        try {
            List<Job> all = jobRepository.findAll();
            var dtoList = all.stream().map(JobDto::from).toList();
            emitter.send(SseEmitter.event().name("jobsSnapshot").data(dtoList));
            // send current worker status as initial event
            try {
                emitter.send(SseEmitter.event().name("workerStatus").data(workerStatus));
            } catch (Exception ignored) {}
        } catch (Exception e) {
            // if initial send fails, remove emitter
            emitters.remove(emitter);
        }

        return emitter;
    }

    /**
     * Return a canonical absolute path for the given file. Attempts to resolve
     * symlinks via `toRealPath()` and falls back to `toAbsolutePath().normalize()`.
     */
    public String canonicalizePath(java.nio.file.Path file) {
        // Avoid resolving symlinks to preserve the filename as seen in the watched
        // directory (toRealPath() can return the target path which may differ).
        try {
            return file.toAbsolutePath().normalize().toString();
        } catch (Exception ex) {
            return file.toString();
        }
    }

    public void publishJob(Job job) {
        var dto = JobDto.from(job);
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name("job").data(dto));
            } catch (Exception ex) {
                emitters.remove(e);
            }
        }
    }

    public void publishWorkerStatus(String status) {
        if (status == null) status = "unknown";
        workerStatus = status;
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name("workerStatus").data(status));
            } catch (Exception ex) {
                emitters.remove(e);
            }
        }
    }

    public void publishDelete(Long id) {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name("jobDeleted").data(id));
            } catch (Exception ex) {
                emitters.remove(e);
            }
        }
    }

    public void publishClear() {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name("indexCleared").data(true));
                // follow with an empty snapshot
                List<Job> all = jobRepository.findAll();
                e.send(SseEmitter.event().name("jobsSnapshot").data(all.stream().map(JobDto::from).toList()));
            } catch (Exception ex) {
                emitters.remove(e);
            }
        }
    }
}
