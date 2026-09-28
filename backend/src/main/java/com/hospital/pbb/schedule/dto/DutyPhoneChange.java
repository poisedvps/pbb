package com.hospital.pbb.schedule.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 一周的值班电话改动（任务单 M4-09，设计 §8.5“暂存”）。
 *
 * <p>{@code weekStart} 必须是周一（不是周一 → code=1505），且该周要与接口路径指定的月份有交集
 * （无交集 → code=1506）——跨月那一周在相邻两个月各出现一次，改的是同一行。</p>
 *
 * @param weekStart 该周周一
 * @param staffId   负责人 id；<b>null 表示清除该周</b>
 */
public record DutyPhoneChange(@NotNull LocalDate weekStart, Long staffId) {}
