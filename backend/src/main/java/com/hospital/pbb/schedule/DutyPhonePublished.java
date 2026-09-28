package com.hospital.pbb.schedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * 一周的值班电话已发布快照（设计 §8.3 duty_phone_published）。
 *
 * <p>和 {@link DutyPhoneWeek} 的关系同 {@code schedule_published_entry} 对
 * {@code schedule_entry}：发布时整体复制当期草稿，成员、大屏、统计只读这一张表。</p>
 */
@Entity
@Table(name = "duty_phone_published")
public class DutyPhonePublished {

    /** 周一；主键，必须落在周一（库里有 ISODOW=1 的 CHECK 约束） */
    @Id
    @Column(name = "week_start")
    private LocalDate weekStart;

    @Column(name = "staff_id", nullable = false)
    private Long staffId;

    public LocalDate getWeekStart() {
        return weekStart;
    }

    public void setWeekStart(LocalDate weekStart) {
        this.weekStart = weekStart;
    }

    public Long getStaffId() {
        return staffId;
    }

    public void setStaffId(Long staffId) {
        this.staffId = staffId;
    }
}
