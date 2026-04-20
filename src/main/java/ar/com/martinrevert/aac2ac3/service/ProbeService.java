package ar.com.martinrevert.aac2ac3.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.io.InputStream;

public interface ProbeService {
    JsonNode probe(File file) throws Exception;

    JsonNode probePath(String inputPath) throws Exception;

    JsonNode probeStream(InputStream inputStream) throws Exception;
}
