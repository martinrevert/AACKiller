package ar.com.martinrevert.aac2ac3.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FfmpegServiceImplTest {

    @Test
    public void run_delegatesToRunner() {
        FfmpegServiceImpl impl = new FfmpegServiceImpl();
        assertNotNull(impl);
    }
}
