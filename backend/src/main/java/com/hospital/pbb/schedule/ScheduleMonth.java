package com.hospital.pbb.schedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 一个月的排班状态（设计 §4 schedule_month）。
 *
 * <p>主键就是 {@code yearMonth}（YYYY-MM）。{@code version} 是发布版本号，
 * 每次发布 +1，由服务层维护（不是 JPA 乐观锁字段）。</p>
 */
@Entity
@Table(name = "schedule_month")
public class ScheduleMonth {

    /** YYYY-MM；库里是 CHAR(7)，必须显式声明 CHAR 的 JDBC 类型，否则 ddl-auto=validate 启动失败 */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "year_month", length = 7)
    private String yearMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScheduleStatus status = ScheduleStatus.DRAFT;

    /** 发布版本号，每次发布 +1 */
    @Column(nullable = false)
    private int version = 0;

    /** 最近一次发布时间，从未发布为 null */
    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    /** 最近一次发布的操作人，从未发布为 null */
    @Column(name = "published_by")
    private Long publishedBy;

    /** 最近一次按规则生成所用的周期模板 id；为 null 时按内置规则 */
    @Column(name = "cycle_template_id")
    private Long cycleTemplateId;

    public String getYearMonth() {
        return yearMonth;
    }

    public void setYearMonth(String yearMonth) {
        this.yearMonth = yearMonth;
    }

    public ScheduleStatus getStatus() {
        return status;
    }

    public void setStatus(ScheduleStatus status) {
        this.status = status;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(OffsetDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Long getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(Long publishedBy) {
        this.publishedBy = publishedBy;
    }

    public Long getCycleTemplateId() {
        return cycleTemplateId;
    }

    public void setCycleTemplateId(Long cycleTemplateId) {
        this.cycleTemplateId = cycleTemplateId;
    }
}
