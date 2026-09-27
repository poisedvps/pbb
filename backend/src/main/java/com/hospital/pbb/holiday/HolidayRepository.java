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

    /**
     * 按年份取 PostgreSQL 事务级 advisory lock，提交或回滚时自动释放。
     *
     * <p>“同一年日期不得重叠”只能由应用层判断（表上没有防重叠约束，迁移文件禁止修改），
     * 所以所有写操作都先按涉及的年份取锁，在锁内重新查询、校验再写，
     * 否则两个管理员同时向原本空白的同一年录入相交区间会双双通过。</p>
     *
     * <p>号段 {@code 1400} 专用于 holiday 表（与 holiday 的错误码段一致），第二个参数是年份，
     * 因此不会和全库其它地方按年份取的 advisory lock 撞号。</p>
     */
    @Query(value = "select pg_advisory_xact_lock(1400, :year)", nativeQuery = true)
    void lockYear(@Param("year") int year);
}
