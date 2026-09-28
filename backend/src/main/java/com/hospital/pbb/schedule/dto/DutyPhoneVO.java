package com.hospital.pbb.schedule.dto;

import java.time.LocalDate;

/**
 * 某一周的值班电话安排（设计 §8.4）。
 *
 * <p>一周一条，{@code weekStart} 是周一、{@code weekEnd} 固定是 {@code weekStart + 6}，
 * 前端按这个区间与当月的交集整格标亮（§8.5“标亮”）。
 * 出于 §8.6，这里只带姓名，任何手机号都不允许出现在月视图接口上。</p>
 *
 * @param weekStart 该周周一，可能落在上个月
 * @param weekEnd   该周周日
 * @param staffId   负责人 id
 * @param name      负责人姓名，人员已查不到时为空串
 */
public record DutyPhoneVO(LocalDate weekStart, LocalDate weekEnd, Long staffId, String name) {}
