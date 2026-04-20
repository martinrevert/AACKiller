package ar.com.martinrevert.aac2ac3.service;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.protocol.commons.EnumWithValue;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

@Service
public class SambaService {
    private static final Logger log = LoggerFactory.getLogger(SambaService.class);

    public List<BrowseEntry> browseDirectories(SmbConnectionRequest req) throws IOException {
        String relDir = ScanPathSettingsService.normalizeSmbPath(req.path());
        List<BrowseEntry> out = new ArrayList<>();
        withShare(req.host(), req.share(), req.username(), req.password(), req.domain(), share -> {
            for (FileIdBothDirectoryInformation info : listDirectory(share, relDir)) {
                String name = info.getFileName();
                if (".".equals(name) || "..".equals(name)) continue;
                boolean isDir = EnumWithValue.EnumUtils.isSet(info.getFileAttributes(), FileAttributes.FILE_ATTRIBUTE_DIRECTORY);
                if (!isDir) continue;
                String childPath = relDir.isBlank() ? name : relDir + "/" + name;
                out.add(new BrowseEntry(name, childPath));
            }
            return null;
        });
        return out;
    }

    public List<String> listMkvRecursively(String smbRootUri, ScanPathSettingsService.SambaConfig cfg) throws IOException {
        ParsedSmbPath root = parseUri(smbRootUri);
        List<String> out = new ArrayList<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(ScanPathSettingsService.normalizeSmbPath(root.path()));

        withShare(root.host(), root.share(), cfg.username(), cfg.password(), cfg.domain(), share -> {
            while (!queue.isEmpty()) {
                String relDir = queue.removeFirst();
                for (FileIdBothDirectoryInformation info : listDirectory(share, relDir)) {
                    String name = info.getFileName();
                    if (".".equals(name) || "..".equals(name)) continue;
                    boolean isDir = EnumWithValue.EnumUtils.isSet(info.getFileAttributes(), FileAttributes.FILE_ATTRIBUTE_DIRECTORY);
                    String childRel = relDir.isBlank() ? name : relDir + "/" + name;
                    if (isDir) {
                        queue.addLast(childRel);
                    } else if (name.toLowerCase().endsWith(".mkv")) {
                        out.add(buildUri(root.host(), root.share(), childRel));
                    }
                }
            }
            return null;
        });
        return out;
    }

    public void rename(String smbUriFrom, String smbUriTo, ScanPathSettingsService.SambaConfig cfg) throws IOException {
        ParsedSmbPath from = parseUri(smbUriFrom);
        ParsedSmbPath to = parseUri(smbUriTo);
        if (!from.host().equalsIgnoreCase(to.host()) || !from.share().equalsIgnoreCase(to.share())) {
            throw new IOException("SMB rename requires same host/share");
        }
        withShare(from.host(), from.share(), cfg.username(), cfg.password(), cfg.domain(), share -> {
            try (File f = share.openFile(
                    toWindowsPath(from.path()),
                    EnumSet.of(AccessMask.DELETE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
            )) {
                f.rename(toWindowsPath(to.path()), true);
            }
            return null;
        });
    }

    public void delete(String smbUri, ScanPathSettingsService.SambaConfig cfg) throws IOException {
        ParsedSmbPath p = parseUri(smbUri);
        withShare(p.host(), p.share(), cfg.username(), cfg.password(), cfg.domain(), share -> {
            share.rm(toWindowsPath(p.path()));
            return null;
        });
    }

    public boolean exists(String smbUri, ScanPathSettingsService.SambaConfig cfg) {
        try {
            ParsedSmbPath p = parseUri(smbUri);
            return withShare(p.host(), p.share(), cfg.username(), cfg.password(), cfg.domain(), share ->
                    share.fileExists(toWindowsPath(p.path())) || share.folderExists(toWindowsPath(p.path()))
            );
        } catch (Exception ex) {
            return false;
        }
    }

    public void downloadToLocal(String smbUri, ScanPathSettingsService.SambaConfig cfg, Path localTarget) throws IOException {
        ParsedSmbPath p = parseUri(smbUri);
        Path parent = localTarget.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        withShare(p.host(), p.share(), cfg.username(), cfg.password(), cfg.domain(), share -> {
            try (File f = share.openFile(
                    toWindowsPath(p.path()),
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
            );
                 InputStream in = f.getInputStream();
                 OutputStream out = Files.newOutputStream(localTarget,
                         StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE)) {
                in.transferTo(out);
            }
            return null;
        });
    }

    public void uploadFromLocal(Path localSource, String smbUri, ScanPathSettingsService.SambaConfig cfg) throws IOException {
        ParsedSmbPath p = parseUri(smbUri);
        withShare(p.host(), p.share(), cfg.username(), cfg.password(), cfg.domain(), share -> {
            try (File f = share.openFile(
                    toWindowsPath(p.path()),
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    null
            );
                 OutputStream out = f.getOutputStream();
                 InputStream in = Files.newInputStream(localSource, StandardOpenOption.READ)) {
                in.transferTo(out);
            }
            return null;
        });
    }

    public <T> T withFileInputStream(String smbUri, ScanPathSettingsService.SambaConfig cfg, FileInputCall<T> call) throws Exception {
        ParsedSmbPath p = parseUri(smbUri);
        return withShare(p.host(), p.share(), cfg.username(), cfg.password(), cfg.domain(), share -> {
            try (File f = share.openFile(
                    toWindowsPath(p.path()),
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
            ); InputStream in = f.getInputStream()) {
                try {
                    return call.apply(in);
                } catch (Exception ex) {
                    if (ex instanceof IOException ioEx) {
                        throw ioEx;
                    }
                    throw new IOException("SMB file callback failed", ex);
                }
            }
        });
    }

    public String withSuffixBeforeExtension(String smbUri, String suffix) {
        ParsedSmbPath p = parseUri(smbUri);
        String rel = p.path();
        int slash = rel.lastIndexOf('/');
        String dir = slash >= 0 ? rel.substring(0, slash) : "";
        String file = slash >= 0 ? rel.substring(slash + 1) : rel;
        int dot = file.lastIndexOf('.');
        String outFile;
        if (dot > 0) {
            outFile = file.substring(0, dot) + suffix + file.substring(dot);
        } else {
            outFile = file + suffix;
        }
        String outPath = dir.isBlank() ? outFile : dir + "/" + outFile;
        return buildUri(p.host(), p.share(), outPath);
    }

    public ParsedSmbPath parseUri(String smbUri) {
        if (smbUri == null || !smbUri.toLowerCase().startsWith("smb://")) {
            throw new IllegalArgumentException("Invalid SMB URI: " + smbUri);
        }
        String rest = smbUri.substring("smb://".length());
        String[] parts = rest.split("/", 3);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Invalid SMB URI (expected smb://host/share/path): " + smbUri);
        }
        String path = parts.length == 3 ? ScanPathSettingsService.normalizeSmbPath(parts[2]) : "";
        return new ParsedSmbPath(parts[0], parts[1], path);
    }

    public String buildUri(String host, String share, String relPath) {
        return ScanPathSettingsService.buildSmbUri(host, share, ScanPathSettingsService.normalizeSmbPath(relPath));
    }

    public String toAuthenticatedSmbUri(String smbUri, ScanPathSettingsService.SambaConfig cfg) {
        ParsedSmbPath p = parseUri(smbUri);
        String user = cfg.username() == null ? "" : cfg.username().trim();
        String pass = cfg.password() == null ? "" : cfg.password();
        String domain = cfg.domain() == null ? "" : cfg.domain().trim();

        StringBuilder auth = new StringBuilder();
        if (!user.isBlank()) {
            if (!domain.isBlank()) {
                auth.append(encodeUserInfoToken(domain)).append(';');
            }
            auth.append(encodeUserInfoToken(user));
            if (!pass.isBlank()) {
                auth.append(':').append(encodeUserInfoToken(pass));
            }
            auth.append('@');
        }

        StringBuilder out = new StringBuilder("smb://")
                .append(auth)
                .append(p.host())
                .append('/')
                .append(encodePathToken(p.share()));
        if (!p.path().isBlank()) {
            out.append('/').append(encodePath(p.path()));
        }
        return out.toString();
    }

    private static String encodePath(String rawPath) {
        String p = ScanPathSettingsService.normalizeSmbPath(rawPath);
        if (p.isBlank()) {
            return "";
        }
        String[] segments = p.split("/");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) out.append('/');
            out.append(encodePathToken(segments[i]));
        }
        return out.toString();
    }

    private static String encodePathToken(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeUserInfoToken(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String toWindowsPath(String relPath) {
        String p = ScanPathSettingsService.normalizeSmbPath(relPath);
        if (p.isBlank()) {
            return "\\";
        }
        return p.replace('/', '\\');
    }

    private static List<FileIdBothDirectoryInformation> listDirectory(DiskShare share, String relDir) {
        String winPath = toWindowsPath(relDir);
        try {
            return share.list(winPath);
        } catch (RuntimeException first) {
            // Some SMB servers accept "" for root and others expect "\\".
            if (!relDir.isBlank()) {
                throw first;
            }
            String altRoot = "\\".equals(winPath) ? "" : "\\";
            return share.list(altRoot);
        }
    }

    private <T> T withShare(String host, String shareName, String username, String password, String domain, ShareCall<T> call) throws IOException {
        String user = username == null ? "" : username;
        String pass = password == null ? "" : password;
        String dom = domain == null ? "" : domain;

        try (SMBClient client = new SMBClient();
             Connection connection = client.connect(host)) {
            AuthenticationContext auth = new AuthenticationContext(user, pass.toCharArray(), dom);
            Session session = connection.authenticate(auth);
            try (DiskShare share = (DiskShare) session.connectShare(shareName)) {
                return call.apply(share);
            }
        } catch (RuntimeException ex) {
            log.warn("SMB runtime error host={} share={}", host, shareName, ex);
            throw ex;
        }
    }

    @FunctionalInterface
    private interface ShareCall<T> {
        T apply(DiskShare share) throws IOException;
    }

    @FunctionalInterface
    public interface FileInputCall<T> {
        T apply(InputStream inputStream) throws Exception;
    }

    public record ParsedSmbPath(String host, String share, String path) {}

    public record BrowseEntry(String name, String path) {}

    public record SmbConnectionRequest(String host, String share, String path, String username, String password, String domain) {}
}
