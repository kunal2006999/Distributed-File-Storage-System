package com.kunal.metadata_service.dto;

import lombok.Data;

@Data
public class CreateUserResponse {
    private long id;
    private String name;
    private String username;
    private String email;
}
