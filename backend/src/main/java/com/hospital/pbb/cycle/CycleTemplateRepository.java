package com.hospital.pbb.cycle;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CycleTemplateRepository extends JpaRepository<CycleTemplate, Long> {

    /** 模板列表按 id 升序展示 */
    List<CycleTemplate> findAllByOrderByIdAsc();

    /** 默认模板，一个也没有时返回空（此时按规则生成退回内置规则） */
    Optional<CycleTemplate> findFirstByDefaultTemplateTrue();

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    /** 取 cycle_template 表级事务 advisory lock，提交或回滚时释放；号段 1800 与 cycle 错误码段一致，key 固定传 0 */
    @Query(value = "select pg_advisory_xact_lock(1800, :key)", nativeQuery = true)
    void lockTemplate(@Param("key") int key);
}
