package com.hospital.pbb.staff.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 修改人员。position / phone 传空串表示清空；active=false 时对应账号同步停用。 */
public record UpdateStaffRequest(@NotBlank @Size(max = 32) String name,
                                 @Size(max = 32) String position,
                                 @Size(max = 32) String phone,
                                 boolean schedulable,
                                 boolean active) {}
