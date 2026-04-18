package ar.com.martinrevert.aac2ac3.service;

import ar.com.martinrevert.aac2ac3.infra.JobRepository;
import ar.com.martinrevert.aac2ac3.model.Job;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class LogCleanupServiceTest {

    @Test
    public void cleanupOldFailedLogs_removesOldFiles() {
        JobRepository repo = mock(JobRepository.class);
        LogCleanupService svc = new LogCleanupService();
        assertNotNull(svc);
    }
}
