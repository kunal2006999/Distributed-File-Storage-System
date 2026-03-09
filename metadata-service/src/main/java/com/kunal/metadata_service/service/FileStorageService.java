package com.kunal.metadata_service.service;

import com.kunal.metadata_service.dto.FileResponse;
import com.kunal.metadata_service.entity.FileMetadataEntity;
import com.kunal.metadata_service.entity.UserEntity;
import com.kunal.metadata_service.repository.FileMetadataRepository;
import com.kunal.metadata_service.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FileStorageService {
    private final FileMetadataRepository repository;
    private final UserRepository useRepository;
    private final ModelMapper modelMapper;
    private static final Logger logger = LoggerFactory.getLogger(FileStorageService.class);

    @Value("${file.storage.path}")
    private String storagePath;

    @Transactional
    public FileResponse uploadFile(MultipartFile file, long ownerId) throws IOException {

        String id = UUID.randomUUID().toString();

        Path path = Paths.get(storagePath, id + "_" + file.getOriginalFilename());


        try {
            logger.info("File upload started: {}", file.getOriginalFilename());
            Files.createDirectories(path.getParent());

            Files.write(path, file.getBytes());
            UserEntity owner = useRepository.findById(ownerId)
            .orElseThrow(() -> new RuntimeException("User not found with ID: " + ownerId));

            FileMetadataEntity metadata = FileMetadataEntity.builder()
                    .id(id)
                    .filename(file.getOriginalFilename())
                    .size(file.getSize())
                    .path(path.toString())
                    .owner(owner)
                    .uploadTime(LocalDateTime.now())
                    .status(Boolean.FALSE)
                    .build();

            FileMetadataEntity savedMetadata = repository.save(metadata);
            logger.info("File uploaded successfully. FileId: {}", metadata.getId());
            return modelMapper.map(savedMetadata, FileResponse.class);
        } catch(Exception e) {
            Files.deleteIfExists(path);
            throw new RuntimeException("File upload failed. Disk and DB rolled back. Error: " + e.getMessage());
        }
    }

    public Path getFile(String id) {

        FileMetadataEntity metadata = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("File not found"));

        return Paths.get(metadata.getPath());
    }

    public Page<FileResponse> getFilesForUser(Long userId, int page, int size) {

        Pageable pageable = PageRequest.of(page, size);

        Page<FileMetadataEntity> files = repository.findByOwnerId(userId, pageable);
        logger.info("Fetching files for user {}", userId);

        // Use the Page interface's built-in map function
        return files.map(file -> modelMapper.map(file, FileResponse.class));
    }

    public FileResponse getMetadata(String id) {
        FileMetadataEntity metadata = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("File not found with id: " + id));
        return modelMapper.map(metadata, FileResponse.class);
    }

}
