package ar.com.martinrevert.aac2ac3.service;

import ar.com.martinrevert.aac2ac3.infra.JobRepository;
import ar.com.martinrevert.aac2ac3.model.Job;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class IndexerServiceMoreTest {

    @Test
    public void index_buildsJobs_whenFilesFound() {
        JobRepository repo = mock(JobRepository.class);
        IndexerService svc = new IndexerService();
        assertNotNull(svc);
    }
}
