package com.kunal.dfs.storage_service.dto;

import lombok.AccessLevel;
import lombok.Data;
import lombok.NonNull;
import lombok.Setter;

@Data
@Setter(AccessLevel.NONE)
public class ChunkRequest {
    @NonNull
    private String chunkHash;

    @NonNull
    private byte[] data;

}
