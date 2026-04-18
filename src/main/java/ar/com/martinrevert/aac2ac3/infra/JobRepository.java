package ar.com.martinrevert.aac2ac3.infra;

import ar.com.martinrevert.aac2ac3.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Long> {
    List<Job> findByStatus(String status);
    Optional<Job> findByFilePath(String filePath);
}
