package ar.com.martinrevert.aac2ac3.service;

import ar.com.martinrevert.aac2ac3.util.ProbeUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.io.File;

@Service
public class ProbeServiceImpl implements ProbeService {
    @Override
    public JsonNode probe(File file) throws Exception {
        return ProbeUtil.probe(file);
    }
}
