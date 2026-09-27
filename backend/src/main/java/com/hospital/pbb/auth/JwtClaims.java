package com.hospital.pbb.auth;

import com.hospital.pbb.user.Role;

public record JwtClaims(Long userId, String username, Role role) {}
