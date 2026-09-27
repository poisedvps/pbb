package com.hospital.pbb.user;

/** 登录后放进 SecurityContext 的当前用户 */
public record AuthUser(Long id, String username, Role role, Long staffId) {}
