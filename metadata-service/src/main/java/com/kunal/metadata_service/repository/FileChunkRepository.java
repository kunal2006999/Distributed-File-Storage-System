package com.kunal.metadata_service.repository;

import com.kunal.metadata_service.entity.Chunk;
import com.kunal.metadata_service.entity.FileChunk;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FileChunkRepository extends JpaRepository<FileChunk, Long> {
    @Query("SELECT fc FROM FileChunk fc WHERE fc.file.id = :fileId ORDER BY fc.chunkOrder ASC")
    List<FileChunk> findByFileIdOrderByChunkOrder(@Param("fileId") String fileId);

    List<FileChunk> findByFileId(String fileId);

    @Modifying
    @Transactional
    void deleteByFileId(String fileId);
}
