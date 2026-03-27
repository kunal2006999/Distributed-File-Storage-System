package com.kunal.metadata_service.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class NodeHealthMonitor {

    private static final Logger logger = LoggerFactory.getLogger(NodeHealthMonitor.class);
    private final ConsistentHasher consistentHasher;
    private final RestTemplate restTemplate;

    @Value("${storage.node.url}")
    private List<String> masterNodes;

    private Set<String> activeNodes =  Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Scheduled(fixedRate = 20000)
    @PostConstruct
    public void checkNodesHealth() {
        logger.debug("Janitor: Starting health check for {} nodes", masterNodes.size());
        for(String node: masterNodes) {
            boolean isAvailable = pingNode(node);
            if(isAvailable && !activeNodes.contains(node)) {
                logger.info("Janitor: Node {} is UP. Adding to ring.", node);
                try {
                    consistentHasher.addServer(node);
                    activeNodes.add(node);
                } catch (NoSuchAlgorithmException e) {
                    logger.error("Failed to add node to ring due to hashing error", e);
                    throw new RuntimeException(e);
                }
            } else if(!isAvailable && activeNodes.contains(node)) {
                logger.warn("Janitor: Node {} is DOWN. Removing from ring.", node);
                consistentHasher.removeServer(node);
                activeNodes.remove(node);
            }
        }
    }

    public boolean pingNode(String nodeUrl) {
        try {
            String healthUrl = nodeUrl + "/actuator/health";
            ResponseEntity<String> response = restTemplate.getForEntity(healthUrl, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            logger.error("Health check failed for {}: {}", nodeUrl, e.getMessage());
            return false;
        }
    }

}
