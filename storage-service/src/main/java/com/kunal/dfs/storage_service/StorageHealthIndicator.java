package com.kunal.dfs.storage_service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
public class StorageHealthIndicator implements HealthIndicator {

    @Value("${storage.base-path}")
    private String storagePath;

    @Override
    public Health health() {
        File path = new File(storagePath);
        File diskToCheck = path.exists() ? path : new File(".");
        long freeSpace = diskToCheck.getUsableSpace(); // Real bytes available
        long threshold = 100 * 1024 * 1024; // 100MB safety limit

        if (freeSpace < threshold) {
            return Health.down()
                    .withDetail("reason", "Low disk space")
                    .withDetail("free_space", freeSpace)
                    .build();
        }

        return Health.up()
                .withDetail("free_space", freeSpace)
                .withDetail("path", storagePath)
                .build();
    }

}
