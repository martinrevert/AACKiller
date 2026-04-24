package ar.com.martinrevert.aac2ac3.infra;

import ar.com.martinrevert.aac2ac3.model.ProbeIndexEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProbeIndexRepository extends JpaRepository<ProbeIndexEntry, Long> {
    Optional<ProbeIndexEntry> findByFilePath(String filePath);
}
