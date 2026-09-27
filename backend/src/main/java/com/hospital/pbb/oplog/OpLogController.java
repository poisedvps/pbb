package com.hospital.pbb.oplog;

import com.hospital.pbb.common.ApiResponse;
import com.hospital.pbb.common.PageVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 操作日志查询接口（M3-09）：按时间倒序分页，可按操作人、动作筛选。
 *
 * <p>日志里含账号名和操作内容，只有科长可看。</p>
 */
@RestController
@RequestMapping("/api/logs")
@PreAuthorize("hasRole('ADMIN')")
public class OpLogController {

    private final OpLogQueryService opLogQueryService;

    public OpLogController(OpLogQueryService opLogQueryService) {
        this.opLogQueryService = opLogQueryService;
    }

    @GetMapping("")
    public ApiResponse<PageVO<OperationLog>> page(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action) {
        return ApiResponse.ok(opLogQueryService.page(page, size, username, action));
    }
}
