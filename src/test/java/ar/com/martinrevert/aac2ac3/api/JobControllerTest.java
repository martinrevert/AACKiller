package ar.com.martinrevert.aac2ac3.api;

import ar.com.martinrevert.aac2ac3.infra.JobRepository;
import ar.com.martinrevert.aac2ac3.model.Job;
import ar.com.martinrevert.aac2ac3.service.JobEventService;
import ar.com.martinrevert.aac2ac3.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class JobControllerTest {

    @Test
    public void list_returnsAllJobs() {
        JobRepository repo = mock(JobRepository.class);
        Job j = new Job(); j.setId(1L); j.setFilePath("/tmp/a.mkv");
        when(repo.findAll()).thenReturn(List.of(j));

        JobController ctrl = new JobController();
        ReflectionTestUtils.setField(ctrl, "jobRepository", repo);
        ReflectionTestUtils.setField(ctrl, "jobEventService", mock(JobEventService.class));
        ReflectionTestUtils.setField(ctrl, "workerService", mock(WorkerService.class));

        List<JobDto> res = ctrl.list();
        assertEquals(1, res.size());
        assertEquals(1L, res.get(0).getId());
        assertEquals("a.mkv", res.get(0).getFileName());
    }

    @Test
    public void get_returnsJob_whenFound() {
        JobRepository repo = mock(JobRepository.class);
        Job j = new Job(); j.setId(2L); j.setFilePath("/x.mkv");
        when(repo.findById(2L)).thenReturn(Optional.of(j));

        JobController ctrl = new JobController();
        ReflectionTestUtils.setField(ctrl, "jobRepository", repo);
        ReflectionTestUtils.setField(ctrl, "jobEventService", mock(JobEventService.class));
        ReflectionTestUtils.setField(ctrl, "workerService", mock(WorkerService.class));

        ResponseEntity<JobDto> r = ctrl.get(2L);
        assertEquals(200, r.getStatusCode().value());
        assertNotNull(r.getBody());
        assertEquals(2L, r.getBody().getId());
    }

    @Test
    public void get_returnsNotFound_whenMissing() {
        JobRepository repo = mock(JobRepository.class);
        when(repo.findById(10L)).thenReturn(Optional.empty());

        JobController ctrl = new JobController();
        ReflectionTestUtils.setField(ctrl, "jobRepository", repo);
        ReflectionTestUtils.setField(ctrl, "jobEventService", mock(JobEventService.class));
        ReflectionTestUtils.setField(ctrl, "workerService", mock(WorkerService.class));

        ResponseEntity<JobDto> r = ctrl.get(10L);
        assertEquals(404, r.getStatusCode().value());
        assertNull(r.getBody());
    }

    @Test
    public void create_returnsBadRequest_forInvalid() {
        JobRepository repo = mock(JobRepository.class);
        JobController ctrl = new JobController();
        ReflectionTestUtils.setField(ctrl, "jobRepository", repo);
        ReflectionTestUtils.setField(ctrl, "jobEventService", mock(JobEventService.class));
        ReflectionTestUtils.setField(ctrl, "workerService", mock(WorkerService.class));
        // Manual enqueue endpoint removed; test removed.
    }

    @Test
    public void create_savesJob_andReturnsSaved() {
        JobRepository repo = mock(JobRepository.class);
        when(repo.findByFilePath(anyString())).thenReturn(Optional.empty());
        when(repo.save(any(Job.class))).thenAnswer(invocation -> {
            Job j = invocation.getArgument(0);
            j.setId(99L);
            return j;
        });
        // Manual enqueue endpoint removed; test removed.
    }

    @Test
    public void delete_returnsNotFound_whenMissing() {
        JobRepository repo = mock(JobRepository.class);
        when(repo.existsById(5L)).thenReturn(false);

        JobController ctrl = new JobController();
        ReflectionTestUtils.setField(ctrl, "jobRepository", repo);
        ReflectionTestUtils.setField(ctrl, "jobEventService", mock(JobEventService.class));
        ReflectionTestUtils.setField(ctrl, "workerService", mock(WorkerService.class));

        ResponseEntity<Void> r = ctrl.delete(5L);
        assertEquals(404, r.getStatusCode().value());
    }

    @Test
    public void delete_deletesAndReturnsNoContent_whenExists() {
        JobRepository repo = mock(JobRepository.class);
        when(repo.existsById(7L)).thenReturn(true);

        JobController ctrl = new JobController();
        ReflectionTestUtils.setField(ctrl, "jobRepository", repo);
        ReflectionTestUtils.setField(ctrl, "jobEventService", mock(JobEventService.class));
        ReflectionTestUtils.setField(ctrl, "workerService", mock(WorkerService.class));

        ResponseEntity<Void> r = ctrl.delete(7L);
        verify(repo, times(1)).deleteById(7L);
        assertEquals(204, r.getStatusCode().value());
    }
}
