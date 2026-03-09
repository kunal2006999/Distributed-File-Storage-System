package com.kunal.metadata_service.repository;


import com.kunal.metadata_service.entity.FileMetadataEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FileMetadataRepository extends JpaRepository<FileMetadataEntity, String> {
    Page<FileMetadataEntity> findByOwnerId(Long ownerId, Pageable pageable);
}
