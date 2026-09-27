package com.hospital.pbb.staff.dto;

import com.hospital.pbb.user.Role;

/**
 * 一条人员。
 *
 * <p>{@code role} 取自该人员的登录账号，没有账号时为 null。</p>
 */
public record StaffVO(Long id, String empNo, String name, String position, String phone,
                      boolean schedulable, int sortOrder, boolean active, Role role) {}
