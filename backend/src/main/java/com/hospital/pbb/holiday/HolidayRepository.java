package com.hospital.pbb.holiday;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    List<Holiday> findByYearOrderByStartDateAsc(int year);

    /** 与 [start, end] 有交集的记录，含只沾一天的边界情况 */
    @Query("select h from Holiday h where h.startDate <= :end and h.endDate >= :start")
    List<Holiday> findOverlapping(@Param("start") LocalDate start, @Param("end") LocalDate end);
}
