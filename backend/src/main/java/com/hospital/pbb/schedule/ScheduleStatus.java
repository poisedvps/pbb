package com.hospital.pbb.schedule;

/**
 * 一个月的排班状态（设计 §4 schedule_month.status）。
 *
 * <p>草稿改动后回到 {@link #DRAFT}，已发布快照保持不变直到再次发布（设计 §4 发布流程）。</p>
 */
public enum ScheduleStatus {
    DRAFT,
    PUBLISHED
}
