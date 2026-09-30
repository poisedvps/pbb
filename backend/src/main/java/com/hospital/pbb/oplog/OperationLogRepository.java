package com.hospital.pbb.oplog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {

    Page<OperationLog> findAllByOrderByIdDesc(Pageable pageable);

    Page<OperationLog> findByUsernameOrderByIdDesc(String username, Pageable pageable);

    Page<OperationLog> findByActionOrderByIdDesc(String action, Pageable pageable);

    Page<OperationLog> findByUsernameAndActionOrderByIdDesc(String username, String action, Pageable pageable);

    /** 删除人员时用（设计 §9.4）：按账号 id 删。 */
    @Modifying
    @Query("delete from OperationLog l where l.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);

    /** 删除人员时用（设计 §9.4）：按 target = 工号 删。 */
    @Modifying
    @Query("delete from OperationLog l where l.target = :target")
    int deleteByTarget(@Param("target") String target);

    /** 删除人员时用（设计 §9.4）：按动作 + target 前缀删。 */
    @Modifying
    @Query("delete from OperationLog l where l.action = :action and l.target like :prefix")
    int deleteByActionAndTargetLike(@Param("action") String action, @Param("prefix") String prefix);

    /** 删除人员时用（设计 §9.4）：按动作 + detail 全等删。 */
    @Modifying
    @Query("delete from OperationLog l where l.action = :action and l.detail = :detail")
    int deleteByActionAndDetail(@Param("action") String action, @Param("detail") String detail);
}
