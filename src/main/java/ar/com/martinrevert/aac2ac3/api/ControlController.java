package ar.com.martinrevert.aac2ac3.api;

import ar.com.martinrevert.aac2ac3.service.WorkerService;
import ar.com.martinrevert.aac2ac3.infra.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.stream.Collectors;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/v1/control")
public class ControlController {
    @Autowired
    private WorkerService workerService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private ar.com.martinrevert.aac2ac3.service.JobEventService jobEventService;


    @PostMapping("/start")
    public ResponseEntity<String> start() {
        workerService.start();
        return ResponseEntity.ok("worker started");
    }

    @PostMapping("/stop")
    public ResponseEntity<String> stop() {
        workerService.stop();
        return ResponseEntity.ok("worker stopping");
    }

    // Worker status is pushed to clients via SSE (workerStatus events).
    // The previous /status REST endpoint was removed in favor of server-sent events.

    @PostMapping("/clear-index")
    public ResponseEntity<String> clearIndex(@RequestParam(value = "restart", required = false, defaultValue = "false") boolean restart) {
        // stop worker first to avoid races
        workerService.stop();
        jobRepository.deleteAll();
        try { jobEventService.publishClear(); } catch (Exception ignored) {}
        if (restart) {
            try { workerService.start(); } catch (Exception ignored) {}
            return ResponseEntity.ok("index cleared and worker restarted");
        }
        return ResponseEntity.ok("index cleared");
    }

    // Backwards-compatible overload used by unit tests and internal callers.
    public ResponseEntity<String> clearIndex() {
        return clearIndex(false);
    }
    
        // rescan endpoint removed — DirectoryWatcher/Indexer run automatically

        @PostMapping("/dedupe")
        public ResponseEntity<Object> dedupe(@RequestParam(value = "dryRun", required = false, defaultValue = "true") boolean dryRun) {
            List<ar.com.martinrevert.aac2ac3.model.Job> all = jobRepository.findAll();
            Map<String, List<ar.com.martinrevert.aac2ac3.model.Job>> groups = all.stream().collect(Collectors.groupingBy(ar.com.martinrevert.aac2ac3.model.Job::getFilePath));
            List<Map<String, Object>> report = new ArrayList<>();
            for (Map.Entry<String, List<ar.com.martinrevert.aac2ac3.model.Job>> e : groups.entrySet()) {
                List<ar.com.martinrevert.aac2ac3.model.Job> list = e.getValue();
                if (list.size() <= 1) continue;
                ar.com.martinrevert.aac2ac3.model.Job keeper = list.stream().filter(j -> "DONE".equals(j.getStatus())).findFirst()
                        .orElse(list.stream().filter(j -> "RUNNING".equals(j.getStatus())).findFirst()
                                .orElse(list.stream().filter(j -> "PENDING".equals(j.getStatus())).findFirst().orElse(list.get(0))));
                List<Long> deleteIds = list.stream().filter(j -> !Objects.equals(j.getId(), keeper.getId())).map(ar.com.martinrevert.aac2ac3.model.Job::getId).collect(Collectors.toList());
                Map<String, Object> m = new HashMap<>();
                m.put("filePath", e.getKey());
                m.put("keeperId", keeper.getId());
                m.put("deleteIds", deleteIds);
                m.put("keeperStatus", keeper.getStatus());
                report.add(m);
                if (!dryRun && !deleteIds.isEmpty()) {
                    jobRepository.deleteAllById(deleteIds);
                    deleteIds.forEach(id -> { try { jobEventService.publishDelete(id); } catch (Exception ignored) {} });
                }
            }
            return ResponseEntity.ok(Map.of("dryRun", dryRun, "actions", report));
        }

        @PostMapping("/cleanup-missing")
        public ResponseEntity<Object> cleanupMissing(@RequestParam(value = "dryRun", required = false, defaultValue = "true") boolean dryRun) {
            List<ar.com.martinrevert.aac2ac3.model.Job> all = jobRepository.findAll();
            List<Map<String, Object>> report = new ArrayList<>();
            for (ar.com.martinrevert.aac2ac3.model.Job j : all) {
                Map<String, Object> m = new HashMap<>();
                m.put("jobId", j.getId());
                m.put("filePath", j.getFilePath());
                boolean exists = false;
                try { exists = Files.exists(Path.of(j.getFilePath())); } catch (Exception ignored) {}
                m.put("exists", exists);
                report.add(m);
                if (!dryRun && !exists) {
                    j.setStatus("MISSING");
                    j.setLogsPath("file-not-found");
                    jobRepository.save(j);
                    try { jobEventService.publishJob(j); } catch (Exception ignored) {}
                }
            }
            return ResponseEntity.ok(Map.of("dryRun", dryRun, "report", report));
        }
}
