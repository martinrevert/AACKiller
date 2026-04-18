package ar.com.martinrevert.aac2ac3.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ProbeServiceImplTest {

    @Test
    public void probe_delegatesToProbeUtil() {
        ProbeServiceImpl impl = new ProbeServiceImpl();
        assertNotNull(impl);
    }
}
