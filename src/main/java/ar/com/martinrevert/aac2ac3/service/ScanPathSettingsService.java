package ar.com.martinrevert.aac2ac3.service;

import ar.com.martinrevert.aac2ac3.infra.AppSettingRepository;
import ar.com.martinrevert.aac2ac3.model.AppSetting;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ScanPathSettingsService {
    private static final String KEY_SCAN_PATH = "index.scanPath";
    private static final String KEY_SMB_HOST = "smb.host";
    private static final String KEY_SMB_SHARE = "smb.share";
    private static final String KEY_SMB_BASE_PATH = "smb.basePath";
    private static final String KEY_SMB_USERNAME = "smb.username";
    private static final String KEY_SMB_PASSWORD = "smb.password";
    private static final String KEY_SMB_DOMAIN = "smb.domain";

    private final AppSettingRepository appSettingRepository;

    @Value("${index.scanPath:samples}")
    private String defaultScanPath;

    public ScanPathSettingsService(AppSettingRepository appSettingRepository) {
        this.appSettingRepository = appSettingRepository;
    }

    public String getScanPath() {
        return getValue(KEY_SCAN_PATH, defaultScanPath);
    }

    public void saveLocalScanPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("localPath is required");
        }
        putValue(KEY_SCAN_PATH, path.trim());
    }

    public void saveSambaScanPath(String host, String share, String basePath) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("smbHost is required");
        if (share == null || share.isBlank()) throw new IllegalArgumentException("smbShare is required");

        String cleanBasePath = normalizeSmbPath(basePath);
        putValue(KEY_SCAN_PATH, buildSmbUri(host.trim(), share.trim(), cleanBasePath));
        putValue(KEY_SMB_HOST, host.trim());
        putValue(KEY_SMB_SHARE, share.trim());
        putValue(KEY_SMB_BASE_PATH, cleanBasePath);
    }

    public void saveSambaCredentials(String username, String password, String domain) {
        putValue(KEY_SMB_USERNAME, username == null ? "" : username.trim());
        putValue(KEY_SMB_PASSWORD, password == null ? "" : password);
        putValue(KEY_SMB_DOMAIN, domain == null ? "" : domain.trim());
    }

    public SambaConfig getSambaConfig() {
        return new SambaConfig(
                getValue(KEY_SMB_HOST, ""),
                getValue(KEY_SMB_SHARE, ""),
                normalizeSmbPath(getValue(KEY_SMB_BASE_PATH, "")),
                getValue(KEY_SMB_USERNAME, ""),
                getValue(KEY_SMB_PASSWORD, ""),
                getValue(KEY_SMB_DOMAIN, "")
        );
    }

    public static String buildSmbUri(String host, String share, String basePath) {
        String cleanPath = normalizeSmbPath(basePath);
        StringBuilder sb = new StringBuilder("smb://").append(host).append('/').append(share);
        if (!cleanPath.isEmpty()) {
            sb.append('/').append(cleanPath);
        }
        return sb.toString();
    }

    public static String normalizeSmbPath(String input) {
        if (input == null) return "";
        String p = input.trim().replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private String getValue(String key, String fallback) {
        return appSettingRepository.findBySettingKey(key).map(AppSetting::getSettingValue).orElse(fallback);
    }

    private void putValue(String key, String value) {
        AppSetting setting = appSettingRepository.findBySettingKey(key).orElseGet(AppSetting::new);
        setting.setSettingKey(key);
        setting.setSettingValue(value == null ? "" : value);
        appSettingRepository.save(setting);
    }

    public record SambaConfig(String host, String share, String basePath, String username, String password, String domain) {
        public boolean isConfigured() {
            return host != null && !host.isBlank() && share != null && !share.isBlank();
        }

        public boolean hasCredentials() {
            return username != null && !username.isBlank() && password != null && !password.isBlank();
        }
    }
}
