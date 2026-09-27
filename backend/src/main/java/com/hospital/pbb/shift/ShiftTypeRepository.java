package com.hospital.pbb.shift;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShiftTypeRepository extends JpaRepository<ShiftType, String> {

    /** 班次固定 6 条，按预置的 sort_order 展示，不提供分页 */
    List<ShiftType> findAllByOrderBySortOrderAsc();
}
