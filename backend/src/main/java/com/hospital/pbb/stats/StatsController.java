package com.hospital.pbb.stats;

import com.hospital.pbb.common.ApiResponse;
import com.hospital.pbb.stats.dto.StatsVO;
import com.hospital.pbb.user.AuthUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 统计报表接口（任务单 M3-06）。
 *
 * <p>大屏账号只负责轮播排班，不给看全科室的工时统计，所以限 ADMIN、MEMBER；
 * MEMBER 只看自己那一行，是谁由登录态决定，不接受前端传 {@code staffId}。</p>
 */
@RestController
@RequestMapping("/api/stats")
@PreAuthorize("hasAnyRole('ADMIN','MEMBER')")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /** 区间统计，{@code from}、{@code to} 形如 {@code 2026-10-01}，含首尾两天 */
    @GetMapping("")
    public ApiResponse<StatsVO> stats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(statsService.stats(from, to, me));
    }
}
