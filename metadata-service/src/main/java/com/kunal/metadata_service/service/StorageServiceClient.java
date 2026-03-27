package com.kunal.metadata_service.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class StorageServiceClient {

    private final RestTemplate restTemplate;

    public boolean saveChunk(String storageUrl, String hash, byte[] chunkData) {

        String url = storageUrl + "/chunks/" + hash;
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        ByteArrayResource fileResource = new ByteArrayResource(chunkData) {
            @Override
            public String getFilename() {
                return hash + ".chunk";
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<MultiValueMap<String, Object>>(body, httpHeaders);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return response.getStatusCode() == HttpStatus.CREATED;
        } catch (Exception e) {
            throw new RuntimeException("Failed to send chunk to storage node: " + e.getMessage());
        }
    }

    public Resource fetchChunk(String storageUrl, String hash) {
        String url = storageUrl + "/chunks/" + hash;

        ResponseEntity<Resource> response = restTemplate.getForEntity(url, Resource.class);
        if (response.getStatusCode() == HttpStatus.OK) {
            return response.getBody();
        } else {
            throw new RuntimeException("Could not fetch chunk: " + hash);
        }
    }

    public void deleteChunk(String storageUrl, String hash) {
        String url = storageUrl + "/chunks/" + hash;
        try {
            restTemplate.delete(url);
        } catch (Exception e) {
            throw e;
        }
    }


}
