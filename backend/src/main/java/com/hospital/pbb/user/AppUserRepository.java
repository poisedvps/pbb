package com.hospital.pbb.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    List<AppUser> findAllByOrderByIdAsc();

    /** 人员与账号一对一（app_user.staff_id），人员模块用它同步姓名和启停状态 */
    Optional<AppUser> findByStaffId(Long staffId);
}
