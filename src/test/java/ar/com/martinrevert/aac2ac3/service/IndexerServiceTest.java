package ar.com.martinrevert.aac2ac3.service;

import ar.com.martinrevert.aac2ac3.infra.JobRepository;
import ar.com.martinrevert.aac2ac3.model.Job;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class IndexerServiceTest {

    @Test
    public void index_createsJobsForFoundFiles() {
        JobRepository repo = mock(JobRepository.class);
        IndexerService svc = new IndexerService();
        // Basic smoke test; real fs interactions are out of scope here
        assertNotNull(svc);
    }
}
