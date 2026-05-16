package com.kunal.metadata_service.repository;

import com.kunal.metadata_service.entity.Chunk;
import com.kunal.metadata_service.entity.ChunkLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChunkLocationRepository extends JpaRepository<ChunkLocation, Long> {
    List<ChunkLocation> findByChunk(Chunk chunk);

}
