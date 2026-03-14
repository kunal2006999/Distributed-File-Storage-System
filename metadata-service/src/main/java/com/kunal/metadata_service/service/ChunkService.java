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
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ChunkService {
    private final ChunkRepository chunkRepository;
    private final FileChunkRepository fileChunkRepository;
    private final FileMetadataRepository fileMetadataRepository;
    private static final Logger logger = LoggerFactory.getLogger(FileStorageService.class);

    @Value("${file.storage.path}")
    private String storagePath;
    @Transactional
    public void processChunk(String fileId, byte[] chunkBytes, Integer chunkIndex) throws NoSuchAlgorithmException, IOException {

        logger.info("START - Chunk {} for File {} on Thread [{}]",
                chunkIndex, fileId, Thread.currentThread().getName());

        long startTime = System.currentTimeMillis();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(chunkBytes);
        String hash = HexFormat.of().formatHex(hashBytes);
        String prefix = hash.substring(0, 2);
        Path dir = Paths.get(storagePath, prefix);
        Files.createDirectories(dir);
        Path chunkPath = dir.resolve(hash + ".chunk");
        boolean fileWasCreated = false;

        try {
            if (!Files.exists(chunkPath)) {
                Files.write(chunkPath, chunkBytes);
                fileWasCreated = true;
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
        } catch (Exception e) {
            if (fileWasCreated) {
                try { Files.deleteIfExists(chunkPath); } catch (IOException ignored) {}
            }
            throw e;
        }
    }
}
