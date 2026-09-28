package com.hospital.pbb.cycle.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新增/修改排班周期模板的请求体。
 *
 * <p>{@code days} 必须是 7 个非空班次代号（下标 0 = 周一），个数不足或超出在参数校验层就挡掉，
 * 代号是否真的存在、是否启用交给 {@link com.hospital.pbb.cycle.CycleTemplateService}（1803）。</p>
 */
public record CycleTemplateRequest(@NotBlank @Size(max = 32) String name,
                                   @NotNull @Size(min = 7, max = 7) List<@NotBlank String> days,
                                   boolean isDefault) {}
