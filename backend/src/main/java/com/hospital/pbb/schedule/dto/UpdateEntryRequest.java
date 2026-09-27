package com.hospital.pbb.schedule.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 修改一个排班单元格（任务单 M2-04）。
 *
 * @param staffId   人员 id
 * @param workDate  日期，必须落在接口路径指定的那一个月内
 * @param shiftCode 班次代号；<b>null 表示恢复规则默认</b>（工作日 D、周末和节假日 X）
 * @param remark    备注，空串按无备注存 null
 */
public record UpdateEntryRequest(@NotNull Long staffId, @NotNull LocalDate workDate,
                                 @Size(max = 4) String shiftCode,
                                 @Size(max = 200) String remark) {}
