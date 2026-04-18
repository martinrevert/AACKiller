package ar.com.martinrevert.aac2ac3.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;

@Entity
@Table(name = "jobs")
public class Job {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String filePath;
    private String status; // PENDING, RUNNING, DONE, FAILED
    private long createdAt;
    private long startedAt;
    private long finishedAt;
    private int retryCount;
    private String logsPath;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getStartedAt() { return startedAt; }
    public void setStartedAt(long startedAt) { this.startedAt = startedAt; }
    public long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(long finishedAt) { this.finishedAt = finishedAt; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public String getLogsPath() { return logsPath; }
    public void setLogsPath(String logsPath) { this.logsPath = logsPath; }
}
