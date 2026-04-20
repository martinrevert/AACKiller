package ar.com.martinrevert.aac2ac3.service;

import ar.com.martinrevert.aac2ac3.util.ProbeUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;

@Service
public class ProbeServiceImpl implements ProbeService {
    @Override
    public JsonNode probe(File file) throws Exception {
        return ProbeUtil.probe(file);
    }

    @Override
    public JsonNode probePath(String inputPath) throws Exception {
        return ProbeUtil.probePath(inputPath);
    }

    @Override
    public JsonNode probeStream(InputStream inputStream) throws Exception {
        return ProbeUtil.probeStream(inputStream);
    }
}
