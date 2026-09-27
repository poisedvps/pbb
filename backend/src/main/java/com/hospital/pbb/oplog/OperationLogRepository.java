package com.hospital.pbb.oplog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {

    Page<OperationLog> findAllByOrderByIdDesc(Pageable pageable);

    Page<OperationLog> findByUsernameOrderByIdDesc(String username, Pageable pageable);

    Page<OperationLog> findByActionOrderByIdDesc(String action, Pageable pageable);

    Page<OperationLog> findByUsernameAndActionOrderByIdDesc(String username, String action, Pageable pageable);
}
