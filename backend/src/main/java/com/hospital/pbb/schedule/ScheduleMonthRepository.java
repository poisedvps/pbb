package com.hospital.pbb.schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduleMonthRepository extends JpaRepository<ScheduleMonth, String> {

    /**
     * 按月取 PostgreSQL 事务级 advisory lock，提交或回滚时自动释放，key = 年*100+月（如 202610）。
     *
     * <p>生成、发布、改单元格都要先取到当月的锁再读写，否则两个科长同时发布同一月会交叉写入，
     * 快照里出现两个版本混在一起的数据。</p>
     *
     * <p>号段 {@code 1500} 专用于排班（与 holiday 用的 1400 号段互不冲突），第二个参数是 {@code key}。</p>
     */
    @Query(value = "select pg_advisory_xact_lock(1500, :key)", nativeQuery = true)
    void lockMonth(@Param("key") int key);
}
