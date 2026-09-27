package com.hospital.pbb.auth.dto;

public record LoginResponse(String token, UserInfo user) {}
