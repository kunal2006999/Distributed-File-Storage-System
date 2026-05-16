package com.kunal.metadata_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    @OneToMany(mappedBy = "chunk", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ChunkLocation> locations = new ArrayList<>();

    public void addLocation(ChunkLocation location) {
        if (this.locations == null) {
            this.locations = new ArrayList<>();
        }
        this.locations.add(location);
        location.setChunk(this); // This is the crucial line! It links the location back to this chunk.
    }

}
