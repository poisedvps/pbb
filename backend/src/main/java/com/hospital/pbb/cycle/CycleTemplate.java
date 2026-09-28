package com.hospital.pbb.cycle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 排班周期模板（设计 §8.3 cycle_template）。
 *
 * <p>一个模板规定周一..周日各排什么班，day1=周一、day7=周日，
 * 每个值是 {@code shift_type.code}。按规则生成排班时选定一个模板，
 * 全系统最多一个默认模板（库里用半唯一索引保证）。</p>
 */
@Entity
@Table(name = "cycle_template")
public class CycleTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String name;

    /** 周一 */
    @Column(nullable = false, length = 4)
    private String day1;

    /** 周二 */
    @Column(nullable = false, length = 4)
    private String day2;

    /** 周三 */
    @Column(nullable = false, length = 4)
    private String day3;

    /** 周四 */
    @Column(nullable = false, length = 4)
    private String day4;

    /** 周五 */
    @Column(nullable = false, length = 4)
    private String day5;

    /** 周六 */
    @Column(nullable = false, length = 4)
    private String day6;

    /** 周日 */
    @Column(nullable = false, length = 4)
    private String day7;

    /** 是否为默认模板，全表最多一个 */
    @Column(name = "is_default", nullable = false)
    private boolean defaultTemplate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    /** 周一..周日 7 个班次代号 */
    public List<String> days() {
        return List.of(day1, day2, day3, day4, day5, day6, day7);
    }

    /** days.size() 必须是 7，下标 0=周一 */
    public void setDays(List<String> days) {
        day1 = days.get(0);
        day2 = days.get(1);
        day3 = days.get(2);
        day4 = days.get(3);
        day5 = days.get(4);
        day6 = days.get(5);
        day7 = days.get(6);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDay1() {
        return day1;
    }

    public void setDay1(String day1) {
        this.day1 = day1;
    }

    public String getDay2() {
        return day2;
    }

    public void setDay2(String day2) {
        this.day2 = day2;
    }

    public String getDay3() {
        return day3;
    }

    public void setDay3(String day3) {
        this.day3 = day3;
    }

    public String getDay4() {
        return day4;
    }

    public void setDay4(String day4) {
        this.day4 = day4;
    }

    public String getDay5() {
        return day5;
    }

    public void setDay5(String day5) {
        this.day5 = day5;
    }

    public String getDay6() {
        return day6;
    }

    public void setDay6(String day6) {
        this.day6 = day6;
    }

    public String getDay7() {
        return day7;
    }

    public void setDay7(String day7) {
        this.day7 = day7;
    }

    public boolean isDefaultTemplate() {
        return defaultTemplate;
    }

    public void setDefaultTemplate(boolean defaultTemplate) {
        this.defaultTemplate = defaultTemplate;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
