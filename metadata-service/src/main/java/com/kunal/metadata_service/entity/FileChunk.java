package com.kunal.metadata_service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor()
public class FileChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id", referencedColumnName = "id", nullable = false)
    private FileMetadataEntity file;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chunk_id", referencedColumnName = "id", nullable = false)
    private Chunk chunk;

    private Integer chunkOrder;
}
