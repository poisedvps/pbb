package com.hospital.pbb.holiday.dto;

import com.hospital.pbb.holiday.HolidayType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** 新增/修改节假日的请求体；year 不在这里，由开始日期算出。 */
public record HolidayRequest(@NotBlank @Size(max = 32) String name,
                             @NotNull LocalDate startDate,
                             @NotNull LocalDate endDate,
                             @NotNull HolidayType type,
                             @Size(max = 200) String remark) {}
