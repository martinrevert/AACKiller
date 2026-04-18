package ar.com.martinrevert.aac2ac3.service;

import ar.com.martinrevert.aac2ac3.infra.JobRepository;
import ar.com.martinrevert.aac2ac3.model.Job;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

@Service
public class LogCleanupService {
    @Autowired
    private JobRepository jobRepository;

    // run daily
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupOldFailedLogs() {
        List<Job> all = jobRepository.findByStatus("FAILED");
        long cutoff = Instant.now().minusSeconds(60L * 60 * 24 * 30).toEpochMilli();
        for (Job j : all) {
            try {
                if (j.getCreatedAt() < cutoff && j.getLogsPath() != null && !j.getLogsPath().isBlank()) {
                    Path p = Path.of(j.getLogsPath());
                    Files.deleteIfExists(p);
                }
            } catch (Exception ignored) {}
        }
    }
}
