package com.kunal.metadata_service.service;

import com.kunal.metadata_service.dto.FileResponse;
import com.kunal.metadata_service.entity.Chunk;
import com.kunal.metadata_service.entity.FileChunk;
import com.kunal.metadata_service.entity.FileMetadataEntity;
import com.kunal.metadata_service.entity.UserEntity;
import com.kunal.metadata_service.repository.ChunkRepository;
import com.kunal.metadata_service.repository.FileChunkRepository;
import com.kunal.metadata_service.repository.FileMetadataRepository;
import com.kunal.metadata_service.repository.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
@RequiredArgsConstructor
public class FileStorageService {
    private final FileMetadataRepository repository;
    private final UserRepository useRepository;
    private final FileChunkRepository fileChunkRepository;
    private final ChunkRepository chunkRepository;
    private final ModelMapper modelMapper;
    private final ChunkService chunkService;
    private final TransactionTemplate transactionTemplate;
    private final StorageServiceClient storageServiceClient;
    private static final Logger logger = LoggerFactory.getLogger(FileStorageService.class);
    private static final int CHUNK_SIZE = 4 * 1024 * 1024;
    private static final int IO_BUFFER_SIZE = 8 * 1024;
    @Autowired
    @Qualifier("chunkTaskExecutor")
    private Executor chunkTaskExecutor;

    public FileResponse uploadFile(MultipartFile file, long ownerId) throws IOException, NoSuchAlgorithmException {

        String id = UUID.randomUUID().toString();
        boolean success = false;
        logger.info("Initiating file upload. Generated FileId: {}", id);

        Integer chunkIndex = 0;
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        UserEntity owner = useRepository.findById(ownerId)
                .orElseThrow(() -> new RuntimeException("User not found with ID: " + ownerId));

        String originalHash = calculateCheckSum(file);

        FileMetadataEntity metadata = FileMetadataEntity.builder()
                .id(id)
                .filename(file.getOriginalFilename())
                .size(file.getSize())
                .path("/")
                .owner(owner)
                .uploadTime(LocalDateTime.now())
                .status(Boolean.FALSE)
                .checksum(originalHash)
                .build();

        repository.save(metadata);
        logger.debug("Initial metadata saved with status FALSE for FileId: {}", id);

        try(InputStream in = file.getInputStream()) {
            logger.info("File upload started: {}", file.getOriginalFilename());

            byte[] buffer = new byte[CHUNK_SIZE];
            int dataRead = in.read(buffer);
            while(dataRead > -1) {
                byte[] chunkBytes = Arrays.copyOf(buffer, dataRead);
                int currentIndex = chunkIndex;
                logger.debug("Submitting chunk {} for FileId: {}", currentIndex, id);
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        chunkService.processChunk(id, chunkBytes, currentIndex);
                    } catch (Exception e) {
                        logger.error("Error processing chunk {} for FileId: {}", currentIndex, id, e);
                        throw new RuntimeException(e);
                    }
                }, chunkTaskExecutor);

                futures.add(future);
                dataRead = in.read(buffer);
                chunkIndex ++;
            }

            logger.info("All chunks submitted. Waiting for completion for FileId: {}", id);
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((v, ex) -> {
                if (ex != null) {
                    logger.error("Chunk processing failed for FileId: {}", id, ex);
                    throw new RuntimeException("Chunk processing failed", ex);
                }
            }).join();

            metadata.setStatus(Boolean.TRUE);
            FileMetadataEntity savedMetadata = repository.save(metadata);
            success = true;
            logger.info("File uploaded successfully. FileId: {}", metadata.getId());
            return modelMapper.map(savedMetadata, FileResponse.class);
        } catch(Exception e) {
            logger.error("Upload process failed for FileId: {}. Exception: {}", id, e.getMessage());
            throw new RuntimeException("File upload failed. Disk and DB rolled back. Error: " + e.getMessage());
        } finally {
            if (!success) {
                logger.warn("Upload unsuccessful. Triggering cleanup for FileId: {}", id);
                cleanupFailedUpload(id);
            }
        }
    }


    public Page<FileResponse> getFilesForUser(Long userId, int page, int size) {

        Pageable pageable = PageRequest.of(page, size);

        Page<FileMetadataEntity> files = repository.findByOwnerId(userId, pageable);
        logger.info("Fetching files for user {}", userId);

        return files.map(file -> modelMapper.map(file, FileResponse.class));
    }

    public FileResponse getMetadata(String id) {
        logger.debug("Fetching metadata for FileId: {}", id);
        FileMetadataEntity metadata = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("File not found with id: " + id));
        return modelMapper.map(metadata, FileResponse.class);
    }

    public void downloadFile(String id, HttpServletResponse response) throws NoSuchAlgorithmException {

        logger.info("Initiating download for FileId: {}", id);
        FileMetadataEntity metadata = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("File not found"));

        List<FileChunk> chunks =
                fileChunkRepository.findByFileIdOrderByChunkOrder(id);
        logger.debug("Found {} chunks for FileId: {}", chunks.size(), id);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        try (OutputStream out = response.getOutputStream()) {
            DigestOutputStream dos = new DigestOutputStream(out, sha256);
            for(FileChunk f: chunks) {
                String hash = f.getChunk().getChunkHash();
                Resource resource = null;
                try {
                    resource = storageServiceClient.fetchChunk(f.getChunk().getStorageNodeUrl(), hash);
                } catch(Exception e) {
                    logger.error("Node {} down, trying next candidate for download", f.getChunk().getStorageNodeUrl());
                    throw new RuntimeException(e);
                }
                try (InputStream fis = resource.getInputStream()) {
                    fis.transferTo(dos);
                }
            }
            byte[] digest = sha256.digest();
            String reconstructedHash = bytesToHex(digest);
            if (!reconstructedHash.equals(metadata.getChecksum())) {
                logger.error("INTEGRITY CRITICAL: File {} is corrupted!", id);
            }
            logger.info("File reconstruction completed for file {}", id);
            dos.flush();
        } catch (IOException e) {
            logger.error("IOException during file download for FileId: {}", id, e);
            e.printStackTrace();
        }
    }

    @Transactional
    public void cleanupFailedUpload(String fileId) {
        transactionTemplate.execute(status -> {
            logger.warn("Cleaning up failed upload for file: {}", fileId);
            List<FileChunk> mappings = fileChunkRepository.findByFileId(fileId);
            logger.debug("Found {} chunk mappings to clean up for FileId: {}", mappings.size(), fileId);

            logger.debug("Deleting file chunks mapping for FileId: {}", fileId);
            fileChunkRepository.deleteByFileId(fileId);
            fileChunkRepository.flush();

            for (FileChunk mapping : mappings) {
                String hash = mapping.getChunk().getChunkHash();
                chunkRepository.findByChunkHash(hash).ifPresent(chunk -> {
                    int newCount = chunk.getReferenceCount() - 1;
                    chunk.setReferenceCount(newCount);
                    logger.debug("Decreased reference count to {} for chunk hash: {}", newCount, hash);
                    if (newCount <= 0) {
                        logger.info("Reference count is 0, deleting physical chunk hash: {}", hash);
                        chunkRepository.delete(chunk);
                        try {
                            storageServiceClient.deleteChunk(mapping.getChunk().getStorageNodeUrl(), hash);
                        } catch(Exception e) {
                            logger.error("Node {} down, trying next candidate for download", mapping.getChunk().getStorageNodeUrl());
                            throw new RuntimeException(e);
                        }
                    } else {
                        chunkRepository.save(chunk);
                    }
                });
            }
            logger.debug("Deleting file metadata for FileId: {}", fileId);
            repository.deleteById(fileId);
            return null;
        });
    }

    public String calculateCheckSum(MultipartFile file) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try(InputStream in = file.getInputStream()) {
            byte[] buffer = new byte[IO_BUFFER_SIZE];
            int dataRead;
            while ((dataRead = in.read(buffer)) != -1) {
                digest.update(buffer, 0, dataRead);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        StringBuilder sb = new StringBuilder();
        for(byte b: digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public String bytesToHex(byte[] digest) throws NoSuchAlgorithmException {
        StringBuilder sb = new StringBuilder();
        for(byte b: digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }


}
