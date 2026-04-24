package ar.com.martinrevert.aac2ac3.api;

import ar.com.martinrevert.aac2ac3.service.WorkerService;
import ar.com.martinrevert.aac2ac3.infra.JobRepository;
import ar.com.martinrevert.aac2ac3.infra.ProbeIndexRepository;
import ar.com.martinrevert.aac2ac3.service.SambaService;
import ar.com.martinrevert.aac2ac3.service.ScanPathSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.stream.Collectors;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/control")
public class ControlController {
    private static final Logger log = LoggerFactory.getLogger(ControlController.class);
    private static final Pattern BACKUP_SUFFIX_PATTERN = Pattern.compile("\\.aac2ac3-backup-\\d+(?=\\.(mkv|mp4)$)", Pattern.CASE_INSENSITIVE);

    @Autowired
    private WorkerService workerService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private ProbeIndexRepository probeIndexRepository;

    @Autowired
    private ar.com.martinrevert.aac2ac3.service.JobEventService jobEventService;

    @Autowired
    private ScanPathSettingsService scanPathSettingsService;

    @Autowired
    private SambaService sambaService;


    @PostMapping("/start")
    public ResponseEntity<String> start() {
        workerService.start();
        return ResponseEntity.ok("worker started");
    }

    @PostMapping("/stop")
    public ResponseEntity<String> stop() {
        workerService.stop();
        return ResponseEntity.ok("worker stopping");
    }

    @GetMapping("/scan-config")
    public ResponseEntity<Object> scanConfig() {
        ScanPathSettingsService.SambaConfig smb = scanPathSettingsService.getSambaConfig();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workerRunning", workerService.isRunning());
        out.put("scanPath", scanPathSettingsService.getScanPath());
        out.put("smbHost", smb.host());
        out.put("smbShare", smb.share());
        out.put("smbPath", smb.basePath());
        out.put("smbUsername", smb.username());
        out.put("smbDomain", smb.domain());
        out.put("smbHasPassword", smb.password() != null && !smb.password().isBlank());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/scan-config")
    public ResponseEntity<Object> saveScanConfig(@RequestBody ScanConfigRequest req) {
        if (workerService.isRunning()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Stop worker before changing scan directory"));
        }

        String mode = req.mode == null ? "local" : req.mode.trim().toLowerCase();
        if ("smb".equals(mode)) {
            if (req.smbHost == null || req.smbHost.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "smbHost is required"));
            }
            if (req.smbShare == null || req.smbShare.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "smbShare is required"));
            }
            if (req.smbUsername == null || req.smbUsername.isBlank() || req.smbPassword == null || req.smbPassword.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "smbUsername and smbPassword are required"));
            }

            try {
                sambaService.browseDirectories(new SambaService.SmbConnectionRequest(
                        req.smbHost,
                        req.smbShare,
                        req.smbPath == null ? "" : req.smbPath,
                        req.smbUsername,
                        req.smbPassword,
                        req.smbDomain == null ? "" : req.smbDomain
                ));
            } catch (Exception ex) {
                log.warn("SMB config validation failed host={} share={} path={} user={} domain={}",
                        req.smbHost,
                        req.smbShare,
                        req.smbPath,
                        req.smbUsername,
                        req.smbDomain,
                        ex);
                String details = (ex.getMessage() == null || ex.getMessage().isBlank())
                    ? ex.getClass().getSimpleName()
                    : ex.getMessage();
                return ResponseEntity.badRequest().body(Map.of("error", "SMB connection validation failed", "details", details));
            }

            scanPathSettingsService.saveSambaCredentials(req.smbUsername, req.smbPassword, req.smbDomain == null ? "" : req.smbDomain);
            scanPathSettingsService.saveSambaScanPath(req.smbHost, req.smbShare, req.smbPath == null ? "" : req.smbPath);
        } else {
            if (req.localPath == null || req.localPath.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "localPath is required"));
            }
            scanPathSettingsService.saveLocalScanPath(req.localPath);
        }

        clearIndex(false);
        return ResponseEntity.ok(Map.of(
                "message", "scan directory saved and index cleared",
                "scanPath", scanPathSettingsService.getScanPath(),
                "workerRunning", workerService.isRunning()
        ));
    }

    @PostMapping("/samba/browse")
    public ResponseEntity<Object> browseSamba(@RequestBody SambaBrowseRequest req) {
        if (req.smbHost == null || req.smbHost.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "smbHost is required"));
        }
        if (req.smbShare == null || req.smbShare.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "smbShare is required"));
        }
        if (req.smbUsername == null || req.smbUsername.isBlank() || req.smbPassword == null || req.smbPassword.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "smbUsername and smbPassword are required"));
        }
        try {
            List<SambaService.BrowseEntry> entries = sambaService.browseDirectories(new SambaService.SmbConnectionRequest(
                    req.smbHost,
                    req.smbShare,
                    req.smbPath == null ? "" : req.smbPath,
                    req.smbUsername,
                    req.smbPassword,
                    req.smbDomain == null ? "" : req.smbDomain
            ));
            return ResponseEntity.ok(Map.of(
                    "path", ScanPathSettingsService.normalizeSmbPath(req.smbPath == null ? "" : req.smbPath),
                    "directories", entries
            ));
        } catch (Exception ex) {
            log.warn("SMB browse failed host={} share={} path={} user={} domain={}",
                    req.smbHost,
                    req.smbShare,
                    req.smbPath,
                    req.smbUsername,
                    req.smbDomain,
                    ex);
            String details = (ex.getMessage() == null || ex.getMessage().isBlank())
                    ? ex.getClass().getSimpleName()
                    : ex.getMessage();
            return ResponseEntity.badRequest().body(Map.of("error", "SMB browse failed", "details", details));
        }
    }

    @PostMapping("/samba/test")
    public ResponseEntity<Object> testSamba(@RequestBody SambaBrowseRequest req) {
        String host = req.smbHost == null ? "" : req.smbHost.trim();
        String share = req.smbShare == null ? "" : req.smbShare.trim();
        String user = req.smbUsername == null ? "" : req.smbUsername.trim();
        String pass = req.smbPassword == null ? "" : req.smbPassword;
        String domain = req.smbDomain == null ? "" : req.smbDomain.trim();
        String path = req.smbPath == null ? "" : req.smbPath;

        if (host.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "smbHost is required"));
        }
        if (share.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "smbShare is required"));
        }
        if (user.isBlank() || pass.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "smbUsername and smbPassword are required"));
        }

        List<Map<String, Object>> steps = new ArrayList<>();

        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            String ips = Arrays.stream(addrs).map(InetAddress::getHostAddress).collect(Collectors.joining(", "));
            steps.add(step("dns", true, ips.isBlank() ? "Resolved host" : "Resolved to: " + ips));
        } catch (Exception ex) {
            String details = ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
            steps.add(step("dns", false, details));
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "SMB test failed",
                    "details", "DNS resolution failed",
                    "steps", steps
            ));
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, 445), 3500);
            steps.add(step("tcp445", true, "Connected to " + host + ":445"));
        } catch (Exception ex) {
            String details = ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
            steps.add(step("tcp445", false, details));
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "SMB test failed",
                    "details", "TCP connection to port 445 failed",
                    "steps", steps
            ));
        }

        try {
            List<SambaService.BrowseEntry> rootEntries = sambaService.browseDirectories(new SambaService.SmbConnectionRequest(
                    host,
                    share,
                    "",
                    user,
                    pass,
                    domain
            ));
            steps.add(step("authShare", true, "Authenticated and opened share. " + rootEntries.size() + " directories listed at root."));
        } catch (Exception ex) {
            String details = ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
            steps.add(step("authShare", false, details));
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "SMB test failed",
                    "details", "Authentication/share access failed",
                    "steps", steps
            ));
        }

        String normalizedPath = ScanPathSettingsService.normalizeSmbPath(path);
        if (!normalizedPath.isBlank()) {
            try {
                List<SambaService.BrowseEntry> entries = sambaService.browseDirectories(new SambaService.SmbConnectionRequest(
                        host,
                        share,
                        normalizedPath,
                        user,
                        pass,
                        domain
                ));
                steps.add(step("path", true, "Path is reachable: " + normalizedPath + " (" + entries.size() + " subdirectories)."));
            } catch (Exception ex) {
                String details = ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
                steps.add(step("path", false, details));
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "SMB test failed",
                        "details", "Configured path is not reachable",
                        "steps", steps
                ));
            }
        } else {
            steps.add(step("path", true, "No path provided; root path check skipped."));
        }

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "message", "SMB connectivity test passed",
                "steps", steps
        ));
    }

    private static Map<String, Object> step(String name, boolean ok, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("ok", ok);
        out.put("message", message == null ? "" : message);
        return out;
    }

    // Worker status is pushed to clients via SSE (workerStatus events).
    // The previous /status REST endpoint was removed in favor of server-sent events.

    @PostMapping("/clear-index")
    public ResponseEntity<String> clearIndex(@RequestParam(value = "restart", required = false, defaultValue = "false") boolean restart) {
        // stop worker first to avoid races
        workerService.stop();
        jobRepository.deleteAll();
        probeIndexRepository.deleteAll();
        try { jobEventService.publishClear(); } catch (Exception ignored) {}
        if (restart) {
            try { workerService.start(); } catch (Exception ignored) {}
            return ResponseEntity.ok("index cleared and worker restarted");
        }
        return ResponseEntity.ok("index cleared");
    }

    // Backwards-compatible overload used by unit tests and internal callers.
    public ResponseEntity<String> clearIndex() {
        return clearIndex(false);
    }

    @PostMapping("/recover-backups")
    public ResponseEntity<Object> recoverBackups(
            @RequestParam(value = "dryRun", required = false, defaultValue = "true") boolean dryRun,
            @RequestParam(value = "overwriteExisting", required = false, defaultValue = "false") boolean overwriteExisting,
            @RequestParam(value = "maxReport", required = false, defaultValue = "200") int maxReport
    ) {
        if (workerService.isRunning()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Stop worker before recovery"));
        }

        String scanPath = scanPathSettingsService.getScanPath();
        boolean smbMode = scanPath != null && scanPath.toLowerCase().startsWith("smb://");

        int backupsFound = 0;
        int restored = 0;
        int skippedExisting = 0;
        int skippedInvalid = 0;
        int errors = 0;
        List<Map<String, Object>> report = new ArrayList<>();

        try {
            if (smbMode) {
                ScanPathSettingsService.SambaConfig cfg = scanPathSettingsService.getSambaConfig();
                List<String> all = sambaService.listMediaRecursively(scanPath, cfg);
                for (String backupUri : all) {
                    String originalUri = restoreTargetPath(backupUri);
                    if (Objects.equals(backupUri, originalUri)) {
                        continue;
                    }
                    backupsFound++;

                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("backup", backupUri);
                    row.put("original", originalUri);

                    try {
                        boolean originalExists = sambaService.exists(originalUri, cfg);
                        if (originalExists && !overwriteExisting) {
                            skippedExisting++;
                            row.put("action", "skip-existing");
                        } else {
                            if (!dryRun) {
                                if (originalExists) {
                                    String quarantine = sambaService.withSuffixBeforeExtension(originalUri, ".broken-" + System.currentTimeMillis());
                                    sambaService.rename(originalUri, quarantine, cfg);
                                    row.put("quarantine", quarantine);
                                }
                                sambaService.rename(backupUri, originalUri, cfg);
                            }
                            restored++;
                            row.put("action", dryRun ? "would-restore" : "restored");
                        }
                    } catch (Exception ex) {
                        errors++;
                        row.put("action", "error");
                        row.put("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                    }

                    if (report.size() < Math.max(1, maxReport)) {
                        report.add(row);
                    }
                }
            } else {
                if (scanPath == null || scanPath.isBlank()) {
                    return ResponseEntity.badRequest().body(Map.of("error", "scanPath is empty"));
                }

                Path root = Path.of(scanPath);
                if (!Files.exists(root)) {
                    return ResponseEntity.badRequest().body(Map.of("error", "scanPath does not exist", "scanPath", scanPath));
                }

                try (var stream = Files.walk(root)) {
                        List<Path> mediaFiles = stream
                            .filter(Files::isRegularFile)
                            .filter(p -> {
                            String lower = p.getFileName().toString().toLowerCase();
                            return lower.endsWith(".mkv") || lower.endsWith(".mp4");
                            })
                            .toList();

                        for (Path backupPath : mediaFiles) {
                        String originalStr = restoreTargetPath(backupPath.toString());
                        if (Objects.equals(backupPath.toString(), originalStr)) {
                            continue;
                        }
                        backupsFound++;

                        Path originalPath = Path.of(originalStr);
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("backup", backupPath.toString());
                        row.put("original", originalPath.toString());

                        try {
                            boolean originalExists = Files.exists(originalPath);
                            if (originalExists && !overwriteExisting) {
                                skippedExisting++;
                                row.put("action", "skip-existing");
                            } else {
                                if (!dryRun) {
                                    if (originalExists) {
                                        Path quarantine = addSuffixBeforeExtension(originalPath, ".broken-" + System.currentTimeMillis());
                                        Files.move(originalPath, quarantine, StandardCopyOption.REPLACE_EXISTING);
                                        row.put("quarantine", quarantine.toString());
                                    }
                                    Files.move(backupPath, originalPath, StandardCopyOption.REPLACE_EXISTING);
                                }
                                restored++;
                                row.put("action", dryRun ? "would-restore" : "restored");
                            }
                        } catch (Exception ex) {
                            errors++;
                            row.put("action", "error");
                            row.put("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                        }

                        if (report.size() < Math.max(1, maxReport)) {
                            report.add(row);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.error("Backup recovery failed", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "backup recovery failed",
                    "details", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
            ));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", smbMode ? "smb" : "local");
        out.put("scanPath", scanPath);
        out.put("dryRun", dryRun);
        out.put("overwriteExisting", overwriteExisting);
        out.put("backupsFound", backupsFound);
        out.put("restored", restored);
        out.put("skippedExisting", skippedExisting);
        out.put("skippedInvalid", skippedInvalid);
        out.put("errors", errors);
        out.put("report", report);
        return ResponseEntity.ok(out);
    }

    private static String restoreTargetPath(String backupPath) {
        if (backupPath == null || backupPath.isBlank()) return backupPath;
        return BACKUP_SUFFIX_PATTERN.matcher(backupPath).replaceFirst("");
    }

    private static Path addSuffixBeforeExtension(Path path, String suffix) {
        String file = path.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String renamed;
        if (dot > 0) {
            renamed = file.substring(0, dot) + suffix + file.substring(dot);
        } else {
            renamed = file + suffix;
        }
        Path parent = path.getParent();
        return parent == null ? Path.of(renamed) : parent.resolve(renamed);
    }
    
        // rescan endpoint removed — DirectoryWatcher/Indexer run automatically

        @PostMapping("/dedupe")
        public ResponseEntity<Object> dedupe(@RequestParam(value = "dryRun", required = false, defaultValue = "true") boolean dryRun) {
            List<ar.com.martinrevert.aac2ac3.model.Job> all = jobRepository.findAll();
            Map<String, List<ar.com.martinrevert.aac2ac3.model.Job>> groups = all.stream().collect(Collectors.groupingBy(ar.com.martinrevert.aac2ac3.model.Job::getFilePath));
            List<Map<String, Object>> report = new ArrayList<>();
            for (Map.Entry<String, List<ar.com.martinrevert.aac2ac3.model.Job>> e : groups.entrySet()) {
                List<ar.com.martinrevert.aac2ac3.model.Job> list = e.getValue();
                if (list.size() <= 1) continue;
                ar.com.martinrevert.aac2ac3.model.Job keeper = list.stream().filter(j -> "DONE".equals(j.getStatus())).findFirst()
                        .orElse(list.stream().filter(j -> "RUNNING".equals(j.getStatus())).findFirst()
                                .orElse(list.stream().filter(j -> "PENDING".equals(j.getStatus())).findFirst().orElse(list.get(0))));
                List<Long> deleteIds = list.stream().filter(j -> !Objects.equals(j.getId(), keeper.getId())).map(ar.com.martinrevert.aac2ac3.model.Job::getId).collect(Collectors.toList());
                Map<String, Object> m = new HashMap<>();
                m.put("filePath", e.getKey());
                m.put("keeperId", keeper.getId());
                m.put("deleteIds", deleteIds);
                m.put("keeperStatus", keeper.getStatus());
                report.add(m);
                if (!dryRun && !deleteIds.isEmpty()) {
                    jobRepository.deleteAllById(deleteIds);
                    deleteIds.forEach(id -> { try { jobEventService.publishDelete(id); } catch (Exception ignored) {} });
                }
            }
            return ResponseEntity.ok(Map.of("dryRun", dryRun, "actions", report));
        }

        @PostMapping("/cleanup-missing")
        public ResponseEntity<Object> cleanupMissing(@RequestParam(value = "dryRun", required = false, defaultValue = "true") boolean dryRun) {
            List<ar.com.martinrevert.aac2ac3.model.Job> all = jobRepository.findAll();
            List<Map<String, Object>> report = new ArrayList<>();
            for (ar.com.martinrevert.aac2ac3.model.Job j : all) {
                Map<String, Object> m = new HashMap<>();
                m.put("jobId", j.getId());
                m.put("filePath", j.getFilePath());
                boolean exists = false;
                try { exists = Files.exists(Path.of(j.getFilePath())); } catch (Exception ignored) {}
                m.put("exists", exists);
                report.add(m);
                if (!dryRun && !exists) {
                    j.setStatus("MISSING");
                    j.setLogsPath("file-not-found");
                    jobRepository.save(j);
                    try { jobEventService.publishJob(j); } catch (Exception ignored) {}
                }
            }
            return ResponseEntity.ok(Map.of("dryRun", dryRun, "report", report));
        }

    public static class ScanConfigRequest {
        public String mode;
        public String localPath;
        public String smbHost;
        public String smbShare;
        public String smbPath;
        public String smbUsername;
        public String smbPassword;
        public String smbDomain;
    }

    public static class SambaBrowseRequest {
        public String smbHost;
        public String smbShare;
        public String smbPath;
        public String smbUsername;
        public String smbPassword;
        public String smbDomain;
    }
}
