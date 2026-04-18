package ar.com.martinrevert.aac2ac3.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;

public interface ProbeService {
    JsonNode probe(File file) throws Exception;
}
