package com.kunal.dfs.storage_service.controller;

import com.kunal.dfs.storage_service.service.ChunkService;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileNotFoundException;
import java.io.IOException;

@RestController
@RequestMapping("/chunks")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChunkController {

    private final ChunkService chunkService;
    private final ModelMapper modelMapper;

    @PostMapping("/{hash}")
    public ResponseEntity<String> saveChunk(@PathVariable String hash, @RequestParam("file") MultipartFile file) throws IOException {
        try {
            boolean isNew = chunkService.saveChunk(file.getBytes(), hash);
            if (isNew) {
                return ResponseEntity.status(HttpStatus.CREATED).body("Stored");
            } else {
                return ResponseEntity.ok("Duplicate skipped (Deduplicated)");
            }
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("Write failed");
        }
    }

    @GetMapping("/{hash}")
    public ResponseEntity<Resource> getChunk(@PathVariable String hash) {
        try {
            Resource resource = chunkService.getChunk(hash);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + hash + ".chunk\"")
                    .body(resource);
        } catch (FileNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{hash}")
    public ResponseEntity<String> deleteChunk(@PathVariable String hash) throws IOException {
        try {
            boolean deleted = chunkService.deleteChunk(hash);
            if (deleted) {
                return ResponseEntity.ok("Chunk deleted successfully");
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Chunk not found");
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error deleting file: " + e.getMessage());
        }
    }

}
