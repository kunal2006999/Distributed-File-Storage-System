package com.kunal.metadata_service.repository;


import com.kunal.metadata_service.entity.FileMetadataEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FileMetadataRepository extends JpaRepository<FileMetadataEntity, String> {
    Page<FileMetadataEntity> findByOwnerId(Long ownerId, Pageable pageable);
    Optional<FileMetadataEntity> findById(String id);
}
