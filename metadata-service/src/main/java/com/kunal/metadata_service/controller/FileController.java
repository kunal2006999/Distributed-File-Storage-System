package com.kunal.metadata_service.controller;

import com.kunal.metadata_service.JwtService;
import com.kunal.metadata_service.dto.FileResponse;
import com.kunal.metadata_service.entity.UserEntity;
import com.kunal.metadata_service.service.FileStorageService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.core.io.PathResource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class FileController {
    private final FileStorageService storageService;
    private final ModelMapper modelMapper;
    private final JwtService jwtService;

    @PostMapping("/upload")
    public ResponseEntity<FileResponse> uploadFile(
            @RequestParam @NotNull MultipartFile file,
            @AuthenticationPrincipal UserEntity currentUser
    ) throws IOException {

        FileResponse metadata = storageService.uploadFile(file, currentUser.getId());

        return ResponseEntity.ok(metadata);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<PathResource> downloadFile(
            @PathVariable String id,
            @AuthenticationPrincipal UserEntity currentUser
    ) {


        FileResponse metadata = storageService.getMetadata(id);

        if (metadata.getOwner().getId() != (currentUser.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Path file = storageService.getFile(id);

        if (!Files.exists(file)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        PathResource resource = new PathResource(file);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=" + file.getFileName())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @GetMapping("/my")
    public ResponseEntity<Page<FileResponse>> getMyFiles(
            @AuthenticationPrincipal UserEntity currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {

        Page<FileResponse> files = storageService.getFilesForUser(currentUser.getId(), page, size);
        return ResponseEntity.ok(files);
    }
}
