package com.kunal.metadata_service;

import com.kunal.metadata_service.entity.FileMetadataEntity;
import com.kunal.metadata_service.repository.FileMetadataRepository;
import com.kunal.metadata_service.service.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class FileCleanupScheduler {

    private static final Logger logger = LoggerFactory.getLogger(FileCleanupScheduler.class);

    private final FileMetadataRepository fileRepository;
    private final FileStorageService storageService;

    public FileCleanupScheduler(FileMetadataRepository fileRepository, FileStorageService storageService) {
        this.fileRepository = fileRepository;
        this.storageService = storageService;
    }

    /**
     * Runs every day at 2:00 AM.
     * Cron expression: Seconds Minutes Hours DayOfMonth Month DayOfWeek
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void executeHardDeleteGarbageCollection() {
        logger.info("Starting background Garbage Collection for deleted files...");

        // Calculate the cutoff time (7 days ago)
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(7);

        // Find all files that were soft-deleted before the cutoff
        List<FileMetadataEntity> expiredFiles =
                fileRepository.findByDeletedTrueAndDeletedAtBefore(cutoffDate);

        if (expiredFiles.isEmpty()) {
            logger.info("No expired files found for cleanup.");
            return;
        }

        int successCount = 0;
        int failCount = 0;

        for (FileMetadataEntity file : expiredFiles) {
            try {
                // REUSE your brilliant logic!
                // cleanupFailedUpload already decreases ref counts, deletes chunks, and drops the metadata!
                storageService.cleanupFailedUpload(file.getId());
                successCount++;
            } catch (Exception e) {
                logger.error("Failed to hard-delete file {}: {}", file.getId(), e.getMessage());
                failCount++;
            }
        }

        logger.info("Garbage Collection finished. Successfully deleted: {}, Failed: {}", successCount, failCount);
    }
}
