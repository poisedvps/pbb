package com.hospital.pbb.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.hospital.pbb.user.Role;

public record CreateUserRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{3,32}$") String username,
                                @NotBlank @Size(max = 32) String displayName,
                                @NotNull Role role) {}
