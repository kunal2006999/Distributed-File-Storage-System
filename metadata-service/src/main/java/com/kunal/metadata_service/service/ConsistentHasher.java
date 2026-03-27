package com.kunal.metadata_service.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

@Service
@RequiredArgsConstructor
@Component
public class ConsistentHasher {

    private final ConcurrentSkipListMap<String, String> tree = new ConcurrentSkipListMap<String, String>();
    private final int numberOfReplicas = 100;
    private static final Logger logger = LoggerFactory.getLogger(NodeHealthMonitor.class);

    public void addServer(String serverUrl) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < numberOfReplicas; i++) {
            String virtualName = serverUrl + ":" + i;
            byte[] hashBytes = digest.digest(virtualName.getBytes(StandardCharsets.UTF_8));
            String hash = HexFormat.of().formatHex(hashBytes);
            tree.put(hash, serverUrl);
        }
    }

    public String getServer(String chunkHash) throws NoSuchAlgorithmException {
        if(tree.isEmpty()) return null;
        SortedMap<String, String> tailMap = tree.tailMap(chunkHash);
        String key = tailMap.isEmpty() ? tree.firstKey() : tailMap.firstKey();
        return tree.get(key);
    }

    public List<String> getCandidateNodes(String chunkHash) {
        if(tree.isEmpty()) {
            logger.error("RING IS EMPTY! Janitor might not have run yet or Bean is not Singleton.");
            return new ArrayList<>();
        }
        SortedMap<String, String> tailMap = tree.tailMap(chunkHash);
        List<String> candidates = new ArrayList<>();
        for (String node : tailMap.values()) {
            if (!candidates.contains(node)) {
                candidates.add(node);
            }
        }
        SortedMap<String, String> headMap = tree.headMap(chunkHash);
        for (String node : headMap.values()) {
            if (!candidates.contains(node)) {
                candidates.add(node);
            }
        }
        logger.info("Found {} candidates for hash {}", candidates.size(), chunkHash);
        return candidates;
    }

    public void removeServer(String serverUrl) {
        tree.entrySet().removeIf(entry -> entry.getValue().equals(serverUrl));
    }
}
