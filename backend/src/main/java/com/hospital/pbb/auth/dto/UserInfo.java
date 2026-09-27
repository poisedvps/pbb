package com.hospital.pbb.auth.dto;

import com.hospital.pbb.user.AppUser;
import com.hospital.pbb.user.Role;

public record UserInfo(Long id, String username, String displayName, Role role, Long staffId,
                       boolean mustChangePassword) {

    public static UserInfo of(AppUser u) {
        return new UserInfo(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole(), u.getStaffId(),
                u.isMustChangePassword());
    }
}
