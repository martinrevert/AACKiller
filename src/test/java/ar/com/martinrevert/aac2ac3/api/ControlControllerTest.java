package ar.com.martinrevert.aac2ac3.api;

import ar.com.martinrevert.aac2ac3.infra.JobRepository;
import ar.com.martinrevert.aac2ac3.infra.ProbeIndexRepository;
import ar.com.martinrevert.aac2ac3.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ControlControllerTest {

    @Test
    public void start_callsWorkerStart_andReturnsOk() {
        WorkerService worker = mock(WorkerService.class);
        JobRepository repo = mock(JobRepository.class);

        ControlController ctrl = new ControlController();
        ReflectionTestUtils.setField(ctrl, "workerService", worker);
        ReflectionTestUtils.setField(ctrl, "jobRepository", repo);

        ResponseEntity<String> resp = ctrl.start();

        verify(worker, times(1)).start();
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("worker started", resp.getBody());
    }

    @Test
    public void stop_callsWorkerStop_andReturnsOk() {
        WorkerService worker = mock(WorkerService.class);
        ControlController ctrl = new ControlController();
        ReflectionTestUtils.setField(ctrl, "workerService", worker);

        ResponseEntity<String> resp = ctrl.stop();

        verify(worker, times(1)).stop();
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("worker stopping", resp.getBody());
    }

    @Test
    public void status_returnsRunningOrStopped() {
        WorkerService worker = mock(WorkerService.class);
        ControlController ctrl = new ControlController();
        ReflectionTestUtils.setField(ctrl, "workerService", worker);
        // /status REST endpoint removed; worker status is pushed via SSE. Test removed.
    }

    @Test
    public void clearIndex_stopsWorker_andDeletesAll() {
        WorkerService worker = mock(WorkerService.class);
        JobRepository repo = mock(JobRepository.class);
        ProbeIndexRepository probeIndexRepo = mock(ProbeIndexRepository.class);

        ControlController ctrl = new ControlController();
        ReflectionTestUtils.setField(ctrl, "workerService", worker);
        ReflectionTestUtils.setField(ctrl, "jobRepository", repo);
        ReflectionTestUtils.setField(ctrl, "probeIndexRepository", probeIndexRepo);

        ResponseEntity<String> resp = ctrl.clearIndex();

        verify(worker, times(1)).stop();
        verify(repo, times(1)).deleteAll();
        verify(probeIndexRepo, times(1)).deleteAll();
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("index cleared", resp.getBody());
    }
}
