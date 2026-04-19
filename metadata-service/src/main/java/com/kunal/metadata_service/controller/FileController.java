package com.kunal.metadata_service.controller;

import com.kunal.metadata_service.JwtService;
import com.kunal.metadata_service.dto.FileResponse;
import com.kunal.metadata_service.entity.UserEntity;
import com.kunal.metadata_service.service.FileStorageService;
import jakarta.servlet.http.HttpServletResponse;
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
import java.security.NoSuchAlgorithmException;
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
    ) throws IOException, NoSuchAlgorithmException {

        FileResponse metadata = storageService.uploadFile(file, currentUser.getId());
        return ResponseEntity.ok(metadata);
    }

    @GetMapping("/{id}/download")
    public void downloadFile(
            @PathVariable String id,
            @AuthenticationPrincipal UserEntity currentUser,
            HttpServletResponse response
    ) throws IOException, NoSuchAlgorithmException {

        FileResponse metadata = storageService.getMetadata(id);
        if (metadata.getOwner().getId() != (currentUser.getId())) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            return;
        }
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + metadata.getFilename() + "\"");

        storageService.downloadFile(id, response);

    }

    @GetMapping("/my")
    public ResponseEntity<Page<FileResponse>> getMyFiles(
            @AuthenticationPrincipal UserEntity currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) throws IOException {

        Page<FileResponse> files = storageService.getFilesForUser(currentUser.getId(), page, size);
        return ResponseEntity.ok(files);
    }
}
