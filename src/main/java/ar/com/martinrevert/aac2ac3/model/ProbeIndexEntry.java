package ar.com.martinrevert.aac2ac3.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "probe_index")
public class ProbeIndexEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String filePath;

    // AAC_MATCH, NO_AAC, PROBE_ERROR
    private String probeStatus;
    private String detectedCodec;
    private String skipReason;

    // Local-file identity for safe cache reuse. Nullable for legacy/SMB entries.
    private Long fileSize;
    private Long fileMtime;

    private long updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getProbeStatus() {
        return probeStatus;
    }

    public void setProbeStatus(String probeStatus) {
        this.probeStatus = probeStatus;
    }

    public String getDetectedCodec() {
        return detectedCodec;
    }

    public void setDetectedCodec(String detectedCodec) {
        this.detectedCodec = detectedCodec;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Long getFileMtime() {
        return fileMtime;
    }

    public void setFileMtime(Long fileMtime) {
        this.fileMtime = fileMtime;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
