package com.hospital.pbb.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** username 长度上限与 app_user.username / operation_log.username 的 VARCHAR(32) 保持一致 */
public record LoginRequest(@NotBlank @Size(max = 32, message = "长度不能超过 32") String username,
                           @NotBlank String password) {}
