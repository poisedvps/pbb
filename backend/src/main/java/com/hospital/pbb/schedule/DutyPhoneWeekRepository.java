package com.hospital.pbb.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface DutyPhoneWeekRepository extends JpaRepository<DutyPhoneWeek, LocalDate> {

    /** 一段日期范围内的值班电话草稿，按周升序（区间两端不要求是周一） */
    List<DutyPhoneWeek> findByWeekStartBetweenOrderByWeekStartAsc(LocalDate from, LocalDate to);
}
