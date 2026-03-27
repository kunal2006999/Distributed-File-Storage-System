package com.kunal.metadata_service.service;

import com.kunal.metadata_service.entity.Chunk;
import com.kunal.metadata_service.entity.FileChunk;
import com.kunal.metadata_service.entity.FileMetadataEntity;
import com.kunal.metadata_service.repository.ChunkRepository;
import com.kunal.metadata_service.repository.FileChunkRepository;
import com.kunal.metadata_service.repository.FileMetadataRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ChunkService {
    private final ChunkRepository chunkRepository;
    private final FileChunkRepository fileChunkRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private final StorageServiceClient storageServiceClient;
    private final ConsistentHasher consistentHasher;
    private final NodeHealthMonitor nodeHealthMonitor;
    private static final Logger logger = LoggerFactory.getLogger(FileStorageService.class);
    private String url = null;


    @Transactional
    public void processChunk(String fileId, byte[] chunkBytes, Integer chunkIndex) throws NoSuchAlgorithmException, IOException {

        logger.info("START - Chunk {} for File {} on Thread [{}]",
                chunkIndex, fileId, Thread.currentThread().getName());

        long startTime = System.currentTimeMillis();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(chunkBytes);
        String hash = HexFormat.of().formatHex(hashBytes);

        List<String> candidate = consistentHasher.getCandidateNodes(hash);
        if (candidate.isEmpty()) {
            throw new RuntimeException("No active storage nodes available");
        }
        url = candidate.get(0);
        boolean uploadSuccessful = false;
        for(String node: candidate) {
            try {
                logger.info("Attempting upload to node: {}", node);
                storageServiceClient.saveChunk(node, hash, chunkBytes);
                url = node;
                uploadSuccessful = true;
                break;
            } catch (Exception e) {
                logger.warn("Node {} failed to accept chunk. Trying next candidate...", node);
            }
        }

        if (!uploadSuccessful) {
            logger.error("CRITICAL: All candidate nodes failed for hash {}", hash);
            throw new RuntimeException("Upload failed: No available storage nodes.");
        }

        Chunk chunk = chunkRepository.findByChunkHash(hash)
                .map(existingChunk -> {
                    existingChunk.setReferenceCount(existingChunk.getReferenceCount() + 1);
                    return chunkRepository.save(existingChunk);
                })
                .orElseGet(() -> {
                    Chunk newChunk = new Chunk();
                    newChunk.setChunkHash(hash);
                    newChunk.setSize((long) chunkBytes.length);
                    newChunk.setReferenceCount(1);
                    newChunk.setCreatedAt(LocalDateTime.now());
                    newChunk.setStorageNodeUrl(url);
                    return chunkRepository.save(newChunk);
                });

        FileMetadataEntity file = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("File not found with id: " + fileId));
        FileChunk fileChunk = new FileChunk();
        fileChunk.setFile(file);
        fileChunk.setChunk(chunk);
        fileChunk.setChunkOrder(chunkIndex);

        fileChunkRepository.save(fileChunk);

        long duration = System.currentTimeMillis() - startTime;
        logger.info("FINISH - Chunk {} for File {} on Thread [{}] (Took {}ms)",
                chunkIndex, fileId, Thread.currentThread().getName(), duration);

    }
}
