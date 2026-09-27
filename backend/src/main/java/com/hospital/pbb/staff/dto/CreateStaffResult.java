package com.hospital.pbb.staff.dto;

/**
 * 新增人员的结果。
 *
 * <p>{@code tempPassword} 只在这次响应里出现一次，后端不存明文，首次登录必须修改。</p>
 */
public record CreateStaffResult(StaffVO staff, String username, String tempPassword) {}
