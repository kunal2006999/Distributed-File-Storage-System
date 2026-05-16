package com.kunal.metadata_service.repository;


import com.kunal.metadata_service.entity.FileMetadataEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface FileMetadataRepository extends JpaRepository<FileMetadataEntity, String> {
    Page<FileMetadataEntity> findByOwnerId(Long ownerId, Pageable pageable);
    Optional<FileMetadataEntity> findById(String id);

    // 1. Used by the user to view their active files
    Page<FileMetadataEntity> findByOwnerIdAndDeletedFalse(Long ownerId, Pageable pageable);

    // 2. Used by the background job to find expired files
    List<FileMetadataEntity> findByDeletedTrueAndDeletedAtBefore(LocalDateTime cutoffTime);
}
