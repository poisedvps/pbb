package com.hospital.pbb.schedule;

import com.hospital.pbb.common.ApiResponse;
import com.hospital.pbb.schedule.dto.MonthScheduleVO;
import com.hospital.pbb.user.AuthUser;
import com.hospital.pbb.user.Role;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 排班查询接口。
 *
 * <p>类上不加 {@code @PreAuthorize}：排班表、大屏都要读这张表，登录即可（含大屏账号）；
 * 角色差别不在能不能读，而在读哪一份——科长读草稿，其余读已发布快照。</p>
 */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleQueryService queryService;

    public ScheduleController(ScheduleQueryService queryService) {
        this.queryService = queryService;
    }

    /** 月视图，{@code yearMonth} 形如 {@code 2026-10}，格式不对返回 code=1500 */
    @GetMapping("/{yearMonth}")
    public ApiResponse<MonthScheduleVO> month(@PathVariable String yearMonth, @AuthenticationPrincipal AuthUser me) {
        boolean draft = me.role() == Role.ADMIN;
        return ApiResponse.ok(queryService.getMonth(yearMonth, draft));
    }
}
