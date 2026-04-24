package ar.com.martinrevert.aac2ac3.service;

import ar.com.martinrevert.aac2ac3.infra.ProbeIndexRepository;
import ar.com.martinrevert.aac2ac3.model.ProbeIndexEntry;
import ar.com.martinrevert.aac2ac3.infra.JobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class IndexerServiceMoreTest {

    @TempDir
    Path tempDir;

    @Test
    public void index_reusesNegativeCacheForUnchangedFile() throws Exception {
        Path media = tempDir.resolve("movie.mkv");
        Files.writeString(media, "dummy");

        JobRepository jobRepository = mock(JobRepository.class);
        when(jobRepository.findByFilePath(anyString())).thenReturn(Optional.empty());

        ProbeService probeService = mock(ProbeService.class);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        when(probeService.probe(any(java.io.File.class)))
                .thenReturn(mapper.readTree("{\"streams\":[{\"codec_name\":\"dts\"}]}"));

        ScanPathSettingsService scanPathSettingsService = mock(ScanPathSettingsService.class);
        when(scanPathSettingsService.getScanPath()).thenReturn(tempDir.toString());

        JobEventService jobEventService = mock(JobEventService.class);
        when(jobEventService.canonicalizePath(any(Path.class))).thenAnswer(inv -> ((Path) inv.getArgument(0)).toAbsolutePath().normalize().toString());

        ProbeIndexRepository probeIndexRepository = mock(ProbeIndexRepository.class);
        Map<String, ProbeIndexEntry> state = new ConcurrentHashMap<>();
        when(probeIndexRepository.findByFilePath(anyString())).thenAnswer(inv -> Optional.ofNullable(state.get(inv.getArgument(0))));
        when(probeIndexRepository.save(any(ProbeIndexEntry.class))).thenAnswer(inv -> {
            ProbeIndexEntry e = inv.getArgument(0);
            state.put(e.getFilePath(), e);
            return e;
        });

        ProbeIndexService probeIndexService = new ProbeIndexService();
        ReflectionTestUtils.setField(probeIndexService, "probeIndexRepository", probeIndexRepository);

        IndexerService svc = new IndexerService();
        ReflectionTestUtils.setField(svc, "jobRepository", jobRepository);
        ReflectionTestUtils.setField(svc, "probeService", probeService);
        ReflectionTestUtils.setField(svc, "jobEventService", jobEventService);
        ReflectionTestUtils.setField(svc, "scanPathSettingsService", scanPathSettingsService);
        ReflectionTestUtils.setField(svc, "sambaService", mock(SambaService.class));
        ReflectionTestUtils.setField(svc, "probeIndexService", probeIndexService);

        svc.indexSince(0L);
        svc.indexSince(0L);

        verify(probeService, times(1)).probe(any(java.io.File.class));
    }

    @Test
    public void index_reprobesWhenFileIdentityChanges_afterNegativeCache() throws Exception {
        Path media = tempDir.resolve("movie.mkv");
        Files.writeString(media, "dummy");

        JobRepository jobRepository = mock(JobRepository.class);
        when(jobRepository.findByFilePath(anyString())).thenReturn(Optional.empty());

        ProbeService probeService = mock(ProbeService.class);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        when(probeService.probe(any(java.io.File.class)))
                .thenReturn(mapper.readTree("{\"streams\":[{\"codec_name\":\"dts\"}]}"));

        ScanPathSettingsService scanPathSettingsService = mock(ScanPathSettingsService.class);
        when(scanPathSettingsService.getScanPath()).thenReturn(tempDir.toString());

        JobEventService jobEventService = mock(JobEventService.class);
        when(jobEventService.canonicalizePath(any(Path.class))).thenAnswer(inv -> ((Path) inv.getArgument(0)).toAbsolutePath().normalize().toString());

        ProbeIndexRepository probeIndexRepository = mock(ProbeIndexRepository.class);
        Map<String, ProbeIndexEntry> state = new ConcurrentHashMap<>();
        when(probeIndexRepository.findByFilePath(anyString())).thenAnswer(inv -> Optional.ofNullable(state.get(inv.getArgument(0))));
        when(probeIndexRepository.save(any(ProbeIndexEntry.class))).thenAnswer(inv -> {
            ProbeIndexEntry e = inv.getArgument(0);
            state.put(e.getFilePath(), e);
            return e;
        });

        ProbeIndexService probeIndexService = new ProbeIndexService();
        ReflectionTestUtils.setField(probeIndexService, "probeIndexRepository", probeIndexRepository);

        IndexerService svc = new IndexerService();
        ReflectionTestUtils.setField(svc, "jobRepository", jobRepository);
        ReflectionTestUtils.setField(svc, "probeService", probeService);
        ReflectionTestUtils.setField(svc, "jobEventService", jobEventService);
        ReflectionTestUtils.setField(svc, "scanPathSettingsService", scanPathSettingsService);
        ReflectionTestUtils.setField(svc, "sambaService", mock(SambaService.class));
        ReflectionTestUtils.setField(svc, "probeIndexService", probeIndexService);

        svc.indexSince(0L);
        Files.writeString(media, "dummy-updated");
        Files.setLastModifiedTime(media, FileTime.fromMillis(System.currentTimeMillis() + 2000));
        svc.indexSince(0L);

        verify(probeService, times(2)).probe(any(java.io.File.class));
    }
}
