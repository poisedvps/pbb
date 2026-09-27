package com.hospital.pbb.shift.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 修改班次。code 与 sortOrder 不在请求里：代号是排班数据的引用，排序由预置脚本决定，都不允许改。
 *
 * <p>startTime / endTime 允许同时为空（备班、请假、休息本来就是无时间班次），
 * 一有一空由 {@link com.hospital.pbb.shift.ShiftTypeService} 拦成 1302。</p>
 */
public record UpdateShiftTypeRequest(
        @NotBlank @Size(max = 16) String name,
        @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @JsonFormat(pattern = "HH:mm") LocalTime endTime,
        boolean crossDay,
        @NotNull @DecimalMin("0") @DecimalMax("24") BigDecimal workHours,
        boolean countsAsWork,
        @NotBlank @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String color,
        boolean enabled) {}
