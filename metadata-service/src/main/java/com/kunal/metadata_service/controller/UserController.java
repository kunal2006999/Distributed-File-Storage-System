package com.kunal.metadata_service.controller;

import com.kunal.metadata_service.dto.CreateUserRequest;
import com.kunal.metadata_service.dto.ErrorResponse;
import com.kunal.metadata_service.dto.CreateUserResponse;
import com.kunal.metadata_service.dto.LoginUserRequest;
import com.kunal.metadata_service.entity.UserEntity;
import com.kunal.metadata_service.service.UserService;
import org.modelmapper.ModelMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/users")
@CrossOrigin(origins = "*")
public class UserController {

    private final UserService userService;
    private final ModelMapper modelMapper;

    public UserController(UserService userService, ModelMapper modelMapper) {
        this.userService = userService;
        this.modelMapper = modelMapper;
    }

    @PostMapping("")
    ResponseEntity<CreateUserResponse> signupUser(@RequestBody CreateUserRequest request) {
        UserEntity savedUser = userService.createUser(request);
        URI savedUserUri = URI.create("/users/" + savedUser.getId());

        return ResponseEntity.created(savedUserUri).body(modelMapper.map(savedUser, CreateUserResponse.class));
    }

    @PostMapping("/login")
    ResponseEntity<Map<String,String>> loginUser(@RequestBody LoginUserRequest request) {
        String token = userService.loginUser(request.getUsername(), request.getPassword());

        return ResponseEntity.ok(Map.of("token", token));

    }

    @ExceptionHandler({
            UserService.UserNotFoundException.class
    })
    ResponseEntity<ErrorResponse> handleUserNotFoundException(Exception ex){
        String message;
        HttpStatus status;

        if(ex instanceof UserService.UserNotFoundException) {
            message = ex.getMessage();
            status = HttpStatus.NOT_FOUND;
        } else {
            message = "something went wrong";
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        ErrorResponse response = ErrorResponse.builder()
                .message(message)
                .build();

        return ResponseEntity.status(status).body(response);
    }

}
