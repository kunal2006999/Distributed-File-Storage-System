package com.kunal.metadata_service.repository;

import com.kunal.metadata_service.entity.Chunk;
import com.kunal.metadata_service.entity.FileMetadataEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ChunkRepository extends JpaRepository<Chunk, Long> {
    Optional<Chunk> findByChunkHash(String hash);
}
