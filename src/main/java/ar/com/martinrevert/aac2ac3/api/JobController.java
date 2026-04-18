package ar.com.martinrevert.aac2ac3.api;

import ar.com.martinrevert.aac2ac3.infra.JobRepository;
import ar.com.martinrevert.aac2ac3.model.Job;
import ar.com.martinrevert.aac2ac3.api.JobDto;
import ar.com.martinrevert.aac2ac3.service.WorkerService;
import ar.com.martinrevert.aac2ac3.service.JobEventService;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {
    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private WorkerService workerService;

    @Autowired
    private JobEventService jobEventService;


    @GetMapping
    public List<JobDto> list() {
        return jobRepository.findAll().stream().map(JobDto::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobDto> get(@PathVariable("id") Long id) {
        return jobRepository.findById(id)
                .map(JobDto::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // manual enqueue via HTTP removed; DirectoryWatcher/Indexer create jobs directly

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        if (!jobRepository.existsById(id)) return ResponseEntity.notFound().build();
        jobRepository.deleteById(id);
        try { jobEventService.publishDelete(id); } catch (Exception ignored) {}
        return ResponseEntity.noContent().build();
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return jobEventService.subscribe();
    }
}
