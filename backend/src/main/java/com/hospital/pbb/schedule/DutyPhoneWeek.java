package com.hospital.pbb.schedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 一周的值班电话草稿（设计 §8.3 duty_phone_week）。
 *
 * <p>一周一行，主键就是周一那天，库里用 CHECK 约束挡住非周一的写入。
 * 草稿只给科长编辑用，成员和大屏看的是 {@link DutyPhonePublished}，
 * 必须发布过才会生效。</p>
 */
@Entity
@Table(name = "duty_phone_week")
public class DutyPhoneWeek {

    /** 周一；主键，必须落在周一（库里有 ISODOW=1 的 CHECK 约束） */
    @Id
    @Column(name = "week_start")
    private LocalDate weekStart;

    @Column(name = "staff_id", nullable = false)
    private Long staffId;

    /** 最近一次修改人，从未保存过为 null */
    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

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

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
