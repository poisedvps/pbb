package com.hospital.pbb.user.dto;

import com.hospital.pbb.user.Role;

import java.time.OffsetDateTime;

public record UserVO(Long id, String username, String displayName, Role role, Long staffId,
                     boolean enabled, boolean locked, OffsetDateTime lastLoginAt) {}
