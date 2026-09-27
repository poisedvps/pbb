package com.hospital.pbb.staff.dto;

import com.hospital.pbb.user.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增人员。工号同时作为登录账号的用户名，所以只允许字母、数字和下划线。
 * 账号的初始密码由后端随机生成，不由前端传入。
 */
public record CreateStaffRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{3,32}$") String empNo,
                                 @NotBlank @Size(max = 32) String name,
                                 @Size(max = 32) String position,
                                 @Size(max = 32) String phone,
                                 boolean schedulable,
                                 @NotNull Role role) {}
