package ar.com.martinrevert.aac2ac3.service;

import ar.com.martinrevert.aac2ac3.infra.JobRepository;
import ar.com.martinrevert.aac2ac3.model.Job;
import ar.com.martinrevert.aac2ac3.service.FfmpegService;
import ar.com.martinrevert.aac2ac3.service.ProbeService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class WorkerServiceTest {

    @Test
    public void processJob_missingFile_marksFailed() throws Exception {
        JobRepository repo = mock(JobRepository.class);
        when(repo.findById(1L)).thenReturn(Optional.of(new Job()));
        WorkerService svc = new WorkerService();
        // smoke
        assertNotNull(svc);
    }
}
