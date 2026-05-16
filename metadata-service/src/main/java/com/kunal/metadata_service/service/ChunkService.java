package com.kunal.metadata_service.service;

import com.kunal.metadata_service.entity.*;
import com.kunal.metadata_service.repository.ChunkLocationRepository;
import com.kunal.metadata_service.repository.ChunkRepository;
import com.kunal.metadata_service.repository.FileChunkRepository;
import com.kunal.metadata_service.repository.FileMetadataRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;


import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class ChunkService {
    private final ChunkRepository chunkRepository;
    private final FileChunkRepository fileChunkRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private final StorageServiceClient storageServiceClient;
    private final ConsistentHasher consistentHasher;
    private final NodeHealthMonitor nodeHealthMonitor;
    private final ChunkLocationRepository chunkLocationRepository;
    private static final Logger logger = LoggerFactory.getLogger(FileStorageService.class);

    @Value("${storage.replication.factor:2}") // The :2 is a default fallback!
    private int targetReplicationFactor;


    @Transactional
    public void processChunk(String fileId, byte[] chunkBytes, Integer chunkIndex) throws NoSuchAlgorithmException, IOException {

        logger.info("START - Chunk {} for File {} on Thread [{}]",
                chunkIndex, fileId, Thread.currentThread().getName());

        long startTime = System.currentTimeMillis();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(chunkBytes);
        String hash = HexFormat.of().formatHex(hashBytes);

        FileMetadataEntity file = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("File not found with id: " + fileId));

        Chunk finalChunk; // We will use this at the very end to link the FileChunk

        Optional<Chunk> existingChunk = chunkRepository.findByChunkHash(hash);

        if (existingChunk.isPresent()) {
            // A. Deduplication Path: Chunk already exists in the system!
            logger.info("Chunk {} already exists globally. Incrementing reference count.", hash);
            finalChunk = existingChunk.get();
            finalChunk.setReferenceCount(finalChunk.getReferenceCount() + 1);
            finalChunk = chunkRepository.save(finalChunk);

        } else {

            List<String> candidate = consistentHasher.getCandidateNodes(hash);
            if (candidate.isEmpty()) {
                throw new RuntimeException("No active storage nodes available");
            }

            List<String> successfulNodes = new ArrayList<>();

            Iterator<String> nodeIterator = candidate.iterator();

            while (successfulNodes.size() < targetReplicationFactor && nodeIterator.hasNext()) {

                int nodesNeeded = targetReplicationFactor - successfulNodes.size();
                List<CompletableFuture<String>> batchFutures = new ArrayList<>();

                // 1. Launch the network requests CONCURRENTLY
                for (int i = 0; i < nodesNeeded && nodeIterator.hasNext(); i++) {
                    String node = nodeIterator.next();

                    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                        logger.info("Attempting concurrent upload to node: {}", node);
                        // Blocking network call happens on a separate thread
                        storageServiceClient.saveChunk(node, hash, chunkBytes);
                        return node; // Return the node URL if successful
                    });

                    batchFutures.add(future);
                }

                // 2. Wait for this specific batch to finish and collect successes
                for (CompletableFuture<String> future : batchFutures) {
                    try {
                        // join() waits for the thread to finish.
                        // If saveChunk threw an exception, join() will throw a CompletionException here.
                        String successfulNode = future.join();
                        successfulNodes.add(successfulNode);
                    } catch (Exception e) {
                        logger.warn("A node failed during concurrent upload for chunk {}. Will try fallback candidate...", hash);
                    }
                }
            }

            if (successfulNodes.isEmpty()) {
                // Total failure. Not a single node accepted the file.
                logger.error("CRITICAL: All candidate nodes failed for hash {}", hash);
                throw new RuntimeException("Upload failed: No available storage nodes could accept the chunk.");
            }

            if (successfulNodes.size() < targetReplicationFactor) {
                logger.error("Upload failed: Only {}/{} nodes succeeded. Rolling back partial uploads...",
                        successfulNodes.size(), targetReplicationFactor);

                // Rollback physical files from the nodes that DID succeed
                for (String successNode : successfulNodes) {
                    try {
                        storageServiceClient.deleteChunk(successNode, hash);
                    } catch (Exception rollbackEx) {
                        logger.error("CRITICAL: Failed to delete orphaned chunk {} on {}", hash, successNode, rollbackEx);
                    }
                }
                throw new RuntimeException("Upload failed: Could not satisfy replication factor of " + targetReplicationFactor);
            }

            try {

                Chunk newChunk = new Chunk();
                newChunk.setChunkHash(hash);
                newChunk.setSize((long) chunkBytes.length);
                newChunk.setReferenceCount(1);
                newChunk.setCreatedAt(LocalDateTime.now());

                if (newChunk.getLocations() == null) {
                    newChunk.setLocations(new ArrayList<>());
                }

                // Assign PRIMARY
                ChunkLocation primaryLoc = new ChunkLocation();
                primaryLoc.setStorageNodeUrl(successfulNodes.get(0));
                primaryLoc.setRole(ReplicaRole.PRIMARY);
                newChunk.addLocation(primaryLoc); // Uses the helper method we made earlier

                ChunkLocation replicaLoc = new ChunkLocation();
                replicaLoc.setStorageNodeUrl(successfulNodes.get(1));
                replicaLoc.setRole(ReplicaRole.REPLICA);
                newChunk.addLocation(replicaLoc);

                finalChunk = chunkRepository.save(newChunk);

            } catch (DataIntegrityViolationException e) {
                // ISSUE FIXED: Race Condition Caught!
                // Another thread uploaded this chunk and saved it to the DB milliseconds before us.
                logger.warn("Concurrent upload detected for chunk {}. Applying deduplication fallback...", hash);

                finalChunk = chunkRepository.findByChunkHash(hash)
                        .orElseThrow(() -> new RuntimeException("Chunk exists but not found during concurrent fallback"));

                finalChunk.setReferenceCount(finalChunk.getReferenceCount() + 1);
                finalChunk = chunkRepository.save(finalChunk);

                // CLEANUP: We must delete the physical files WE just uploaded,
                // because the winning thread already uploaded its own physical files.
                for (String node : successfulNodes) {
                    try {
                        storageServiceClient.deleteChunk(node, hash);
                    } catch (Exception cleanupEx) {
                        logger.error("Failed to clean up redundant physical chunk {} on {}", hash, node, cleanupEx);
                    }
                }
            }
        }

        FileChunk fileChunk = new FileChunk();
        fileChunk.setFile(file);
        fileChunk.setChunk(finalChunk);
        fileChunk.setChunkOrder(chunkIndex);

        fileChunkRepository.save(fileChunk);

        long duration = System.currentTimeMillis() - startTime;
        logger.info("FINISH - Chunk {} for File {} on Thread [{}] (Took {}ms)",
                chunkIndex, fileId, Thread.currentThread().getName(), duration);

    }
}
