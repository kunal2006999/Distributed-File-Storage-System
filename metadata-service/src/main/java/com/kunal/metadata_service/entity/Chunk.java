package com.kunal.metadata_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor()
public class Chunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String chunkHash;

    private Long size;

    @ColumnDefault("1")
    private Integer referenceCount;

    private LocalDateTime createdAt;

    private String storageNodeUrl;

}
