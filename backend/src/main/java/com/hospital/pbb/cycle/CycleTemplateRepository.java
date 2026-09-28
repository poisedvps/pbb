package com.hospital.pbb.cycle;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CycleTemplateRepository extends JpaRepository<CycleTemplate, Long> {

    /** 模板列表按 id 升序展示 */
    List<CycleTemplate> findAllByOrderByIdAsc();

    /** 默认模板，一个也没有时返回空（此时按规则生成退回内置规则） */
    Optional<CycleTemplate> findFirstByDefaultTemplateTrue();

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}
