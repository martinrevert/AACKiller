package ar.com.martinrevert.aac2ac3.api;

import ar.com.martinrevert.aac2ac3.model.Job;

import java.nio.file.Paths;

public class JobDto {
    private Long id;
    private String fileName;
    private String status;
    private long createdAt;
    private long startedAt;
    private long finishedAt;
    private int retryCount;
    private String logsPath;

    public JobDto() {}

    public JobDto(Long id, String fileName, String status, long createdAt, long startedAt, long finishedAt, int retryCount, String logsPath) {
        this.id = id;
        this.fileName = fileName;
        this.status = status;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.retryCount = retryCount;
        this.logsPath = logsPath;
    }

    public Long getId() { return id; }
    public String getFileName() { return fileName; }
    public String getStatus() { return status; }
    public long getCreatedAt() { return createdAt; }
    public long getStartedAt() { return startedAt; }
    public long getFinishedAt() { return finishedAt; }
    public int getRetryCount() { return retryCount; }
    public String getLogsPath() { return logsPath; }

    public static JobDto from(Job j) {
        if (j == null) return null;
        String fileName = null;
        try {
            if (j.getFilePath() != null) fileName = Paths.get(j.getFilePath()).getFileName().toString();
        } catch (Exception ignored) {}
        return new JobDto(j.getId(), fileName, j.getStatus(), j.getCreatedAt(), j.getStartedAt(), j.getFinishedAt(), j.getRetryCount(), j.getLogsPath());
    }
}
