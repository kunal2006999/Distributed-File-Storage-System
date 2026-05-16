package com.kunal.metadata_service.service;

import com.kunal.metadata_service.dto.FileResponse;
import com.kunal.metadata_service.entity.*;
import com.kunal.metadata_service.repository.*;
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
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
@RequiredArgsConstructor
public class FileStorageService {
    private final FileMetadataRepository repository;
    private final UserRepository useRepository;
    private final FileChunkRepository fileChunkRepository;
    private final ChunkRepository chunkRepository;
    private final ChunkLocationRepository chunkLocationRepository;
    private final ModelMapper modelMapper;
    private final ChunkService chunkService;
    private final TransactionTemplate transactionTemplate;
    private final StorageServiceClient storageServiceClient;
    private static final Logger logger = LoggerFactory.getLogger(FileStorageService.class);
    private static final int IO_BUFFER_SIZE = 8 * 1024;

    @Value("${storage.chunk.size:4194304}")
    private int CHUNK_SIZE;

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

        Page<FileMetadataEntity> files = repository.findByOwnerIdAndDeletedFalse(userId, pageable);
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
                boolean chunkDownloaded = false;

                List<ChunkLocation> locations = chunkLocationRepository.findByChunk(f.getChunk());
                locations.sort(Comparator.comparing(loc -> loc.getRole() == ReplicaRole.PRIMARY ? 0 : 1));
                Set<String> attemptedNodes = new HashSet<>();

                for(ChunkLocation location: locations) {
                    String nodeUrl = location.getStorageNodeUrl();
                    if (!attemptedNodes.add(nodeUrl)) continue;

                    try {
                        logger.debug("Attempting to fetch chunk {} from {} node: {}", hash, location.getRole(), nodeUrl);
                        resource = storageServiceClient.fetchChunk(nodeUrl, hash);
                        logger.info("Successfully fetched chunk {} from {} node: {}", hash, location.getRole(), nodeUrl);
                        chunkDownloaded = true;
                        break;
                    } catch(Exception e) {
                        logger.warn("Failed to fetch chunk {} from node {}. Trying next replica.", hash, nodeUrl);
                        //logger.error("Node {} down, trying next candidate for download"); //f.getChunk().getStorageNodeUrl());
                    }
                }
                if(!chunkDownloaded || resource == null) {
                    logger.error("CRITICAL: All replicas failed to serve chunk {}", hash);
                    throw new RuntimeException("Download failed: Chunk " + hash + " is unavailable on all storage nodes.");
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
    public void softDeleteFile(String fileId, Long userId) {
        FileMetadataEntity file = repository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("File not found"));

        if (file.getOwner().getId() != userId) {
            throw new IllegalArgumentException("You do not have permission to delete this file.");
        }

        file.setDeleted(true);
        file.setDeletedAt(LocalDateTime.now());
        repository.save(file);

        logger.info("File {} soft-deleted by user {}", fileId, userId);
    }

    @Transactional
    public void cleanupFailedUpload(String fileId) {
        transactionTemplate.execute(status -> {
            logger.warn("Cleaning up failed upload for file: {}", fileId);
            List<FileChunk> mappings = fileChunkRepository.findByFileId(fileId);
            logger.debug("Found {} chunk mappings to clean up for FileId: {}", mappings.size(), fileId);

            for (FileChunk mapping : mappings) {
                String hash = mapping.getChunk().getChunkHash();
                chunkRepository.findByChunkHash(hash).ifPresent(chunk -> {
                    int newCount = chunk.getReferenceCount() - 1;
                    if (newCount <= 0) {
                        logger.info("Reference count is 0, deleting physical chunk hash: {}", hash);
                        try {
                            List<ChunkLocation> locations = chunk.getLocations();
                            for (ChunkLocation location: locations) storageServiceClient.deleteChunk(location.getStorageNodeUrl(), hash);
                            chunkRepository.delete(chunk);
                        } catch(Exception e) {
                            logger.error("Failed to physically delete chunk {} from storage nodes", hash, e);
                            throw new RuntimeException("Chunk physical deletion failed, aborting DB rollback", e);
                        }
                    } else {
                        chunk.setReferenceCount(newCount);
                        logger.debug("Decreased reference count to {} for chunk hash: {}", newCount, hash);
                        chunkRepository.save(chunk);
                    }
                });
            }

            logger.debug("Deleting file chunks mapping for FileId: {}", fileId);
            fileChunkRepository.deleteByFileId(fileId);
            fileChunkRepository.flush();

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
