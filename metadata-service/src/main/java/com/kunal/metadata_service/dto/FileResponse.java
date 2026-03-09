package com.kunal.metadata_service.dto;

import jakarta.persistence.Id;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FileResponse {

    @Id
    private String id;

    private String filename;

    private Long size;

    private CreateUserResponse owner;

    private LocalDateTime uploadTime;
}
