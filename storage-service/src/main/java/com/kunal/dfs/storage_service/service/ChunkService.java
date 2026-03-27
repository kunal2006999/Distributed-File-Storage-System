package com.kunal.dfs.storage_service.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@RequiredArgsConstructor
public class ChunkService {

    private static final Logger logger = LoggerFactory.getLogger(ChunkService.class);
    private static final int IO_BUFFER_SIZE = 8 * 1024;

    @Value("${storage.base-path}")
    private String storagePath;

    public boolean saveChunk(byte[] chunkBytes, String hash) throws IOException {
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
            return fileWasCreated;
        }  catch (IOException e) {
            if (fileWasCreated) {
                try { Files.deleteIfExists(chunkPath); } catch (IOException ignored) {}
            }
            throw e;
        }
    }

    public Resource getChunk(String hash) throws FileNotFoundException {
        String prefix = hash.substring(0,2);
        Path chunkPath = Paths.get(storagePath, prefix, hash + ".chunk");
        logger.debug("Reading chunk from path: {}", chunkPath);
        if (!Files.exists(chunkPath)) {
            logger.error("Physical chunk missing at path: {}", chunkPath);
            throw new FileNotFoundException("Chunk not found: " + hash);
        }

        try {
            return new UrlResource(chunkPath.toUri());
        } catch (MalformedURLException e) {
            throw new RuntimeException("Error resolving file path", e);
        }
    }

    public boolean deleteChunk(String hash) throws IOException {
        String prefix = hash.substring(0,2);
        Path chunkPath = Paths.get(storagePath, prefix, hash + ".chunk");
        try {
            boolean result = Files.deleteIfExists(chunkPath);

            if (result) {
                logger.info("Physical file deleted: {}", chunkPath);
                File dir = chunkPath.getParent().toFile();
                if (dir.isDirectory() && dir.list().length == 0) {
                    dir.delete();
                }
            }
            return result;
        } catch (IOException e) {
            logger.error("IO Error while deleting chunk {}: {}", hash, e.getMessage());
            return false;
        }
    }


}
