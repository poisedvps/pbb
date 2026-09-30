package com.hospital.pbb.staff;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface StaffRepository extends JpaRepository<Staff, Long> {

    List<Staff> findAllByOrderBySortOrderAscIdAsc();

    List<Staff> findByActiveTrueOrderBySortOrderAscIdAsc();

    boolean existsByEmpNo(String empNo);

    /** 同名人数（含停用人员）。删人员时用来判断日志能不能按姓名清理，见 {@link StaffService#delete} */
    long countByName(String name);

    /** 表里没有数据时返回 0，新人员的排序号从 1 开始 */
    @Query("select coalesce(max(s.sortOrder), 0) from Staff s")
    int maxSortOrder();
}
