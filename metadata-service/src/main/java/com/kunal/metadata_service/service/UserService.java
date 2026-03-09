package com.kunal.metadata_service.service;

import com.kunal.metadata_service.JwtService;
import com.kunal.metadata_service.dto.CreateUserRequest;
import com.kunal.metadata_service.entity.UserEntity;
import com.kunal.metadata_service.repository.UserRepository;
import org.modelmapper.ModelMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserRepository usersRepository;
    private final ModelMapper modelMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;


    public UserService(UserRepository usersRepository, ModelMapper modelMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.usersRepository = usersRepository;
        this.modelMapper = modelMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }


    public UserEntity createUser(CreateUserRequest u) {
        UserEntity newUser = modelMapper.map(u, UserEntity.class);
        newUser.setId(0);
        String encodedPassword  = passwordEncoder.encode(u.getPassword());
        newUser.setPassword(encodedPassword);

        return usersRepository.save(newUser);
    }

    public UserEntity getUser(String username) {
        return usersRepository.findByUsername(username).orElseThrow(() -> new UserNotFoundException(username));
    }

    public UserEntity getUser(Long userId) {
        return usersRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    }

    public String  loginUser(String username, String password) {
        var user = usersRepository.findByUsername(username).orElseThrow(() -> new UserNotFoundException(username));
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("Invalid password");
        }
        return jwtService.generateToken(user);
    }

    public static class UserNotFoundException extends IllegalArgumentException {
        public UserNotFoundException(String username) {
            super("User with username" + username + " not found");
        }
        public UserNotFoundException(Long userId) {
            super("User with id" + userId + " not found");
        }
    }
}
