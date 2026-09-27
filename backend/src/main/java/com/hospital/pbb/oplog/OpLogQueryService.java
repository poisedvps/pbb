package com.hospital.pbb.oplog;

import com.hospital.pbb.common.PageVO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * 操作日志的只读查询（M3-09），供科长在"操作日志"页分页倒序查看。
 *
 * <p>写入一律走 {@link OpLogService}，这里只读不写。</p>
 */
@Service
public class OpLogQueryService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final OperationLogRepository repository;

    public OpLogQueryService(OperationLogRepository repository) {
        this.repository = repository;
    }

    /** page 从 0 开始（负数按 0）；size 限制在 1~100（小于 1 按默认 20）；username、action 为空视为不筛选。 */
    public PageVO<OperationLog> page(int page, int size, String username, String action) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size));
        String user = trimToNull(username);
        String act = trimToNull(action);

        Page<OperationLog> result;
        if (user != null && act != null) {
            result = repository.findByUsernameAndActionOrderByIdDesc(user, act, pageable);
        } else if (user != null) {
            result = repository.findByUsernameOrderByIdDesc(user, pageable);
        } else if (act != null) {
            result = repository.findByActionOrderByIdDesc(act, pageable);
        } else {
            result = repository.findAllByOrderByIdDesc(pageable);
        }
        return new PageVO<>(result.getTotalElements(), result.getContent());
    }

    private static int normalizeSize(int size) {
        if (size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    /** 前端筛选框没填时可能传 null、空串或一串空白，三种都算"不筛选" */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
