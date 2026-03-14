package com.kunal.metadata_service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(
        name = "file_metadata",
        indexes = {
                @Index(name = "idx_user_id", columnList = "userId"),
                @Index(name = "idx_upload_time", columnList = "uploadTime")
        }
)
public class FileMetadataEntity {

    @Id
    private String id;

    private String filename;

    private Long size;

    private String path;

    @ManyToOne
    @JoinColumn(name = "user_id", referencedColumnName = "id")
    private UserEntity owner;

    private LocalDateTime uploadTime;

    private Boolean status;

}
