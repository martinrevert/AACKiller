package ar.com.martinrevert.aac2ac3.service;

import ar.com.martinrevert.aac2ac3.infra.JobRepository;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

public class WorkerServiceStartStopTest {

    @Test
    public void start_and_stop_invokeExecutorLifecycle() throws Exception {
        JobRepository repo = mock(JobRepository.class);
        WorkerService svc = new WorkerService();
        // basic lifecycle calls
        svc.start();
        svc.stop();
    }
}
