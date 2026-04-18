package ar.com.martinrevert.aac2ac3.util;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FfmpegCommandBuilderTest {

    @Test
    public void buildsBasicConvert_returnsList() {
        File in = new File("in.mkv");
        File out = new File("out.mkv");
        List<String> cmd = FfmpegCommandBuilder.buildBasicConvert(in, out, 1);
        assertNotNull(cmd);
        assertFalse(cmd.isEmpty());
        assertEquals("ffmpeg", cmd.get(0));
        assertEquals(out.getAbsolutePath(), cmd.get(cmd.size() - 1));
    }
}
