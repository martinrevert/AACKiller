package ar.com.martinrevert.aac2ac3.service;

import ar.com.martinrevert.aac2ac3.infra.ProbeIndexRepository;
import ar.com.martinrevert.aac2ac3.model.ProbeIndexEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ProbeIndexServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void classifyProbe_returnsAacMatch_whenAacStreamExists() throws Exception {
        ProbeIndexService service = new ProbeIndexService();
        var probe = MAPPER.readTree("{\"streams\":[{\"codec_name\":\"aac\"}]}");

        var classification = service.classifyProbe(probe);

        assertEquals(ProbeIndexService.STATUS_AAC_MATCH, classification.probeStatus());
        assertEquals("aac", classification.detectedCodec());
        assertTrue(classification.hasAac());
    }

    @Test
    public void classifyProbe_returnsNoAac_andCodec_whenNoAacStream() throws Exception {
        ProbeIndexService service = new ProbeIndexService();
        var probe = MAPPER.readTree("{\"streams\":[{\"codec_name\":\"dts\"}]}");

        var classification = service.classifyProbe(probe);

        assertEquals(ProbeIndexService.STATUS_NO_AAC, classification.probeStatus());
        assertEquals("dts", classification.detectedCodec());
        assertFalse(classification.hasAac());
    }

    @Test
    public void findReusableLocal_returnsEmpty_forLegacyEntryWithoutStatus() {
        ProbeIndexRepository repo = mock(ProbeIndexRepository.class);
        ProbeIndexService service = new ProbeIndexService();
        ReflectionTestUtils.setField(service, "probeIndexRepository", repo);

        ProbeIndexEntry legacy = new ProbeIndexEntry();
        legacy.setFilePath("/tmp/a.mkv");
        legacy.setFileSize(100L);
        legacy.setFileMtime(200L);
        legacy.setProbeStatus(null);
        when(repo.findByFilePath("/tmp/a.mkv")).thenReturn(Optional.of(legacy));

        assertTrue(service.findReusableLocal("/tmp/a.mkv", 100L, 200L).isEmpty());
    }

    @Test
    public void findReusableLocal_returnsEmpty_whenIdentityChanged() {
        ProbeIndexRepository repo = mock(ProbeIndexRepository.class);
        ProbeIndexService service = new ProbeIndexService();
        ReflectionTestUtils.setField(service, "probeIndexRepository", repo);

        ProbeIndexEntry entry = new ProbeIndexEntry();
        entry.setFilePath("/tmp/a.mkv");
        entry.setFileSize(100L);
        entry.setFileMtime(200L);
        entry.setProbeStatus(ProbeIndexService.STATUS_NO_AAC);
        when(repo.findByFilePath("/tmp/a.mkv")).thenReturn(Optional.of(entry));

        assertTrue(service.findReusableLocal("/tmp/a.mkv", 100L, 201L).isEmpty());
    }
}
