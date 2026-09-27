package com.hospital.pbb.schedule;

import com.hospital.pbb.common.ApiResponse;
import com.hospital.pbb.schedule.dto.MineVO;
import com.hospital.pbb.user.AuthUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "我的排班"接口。
 *
 * <p>路径 {@code /api/schedules/mine} 是字面量，Spring 的路径匹配优先于
 * {@link ScheduleController} 上的 {@code /api/schedules/{yearMonth}}，
 * 所以 {@code mine} 不会被当成月份；成员、科长、大屏账号都能访问（登录即可），
 * 各自看的都是同一份已发布快照，没有草稿的差别。</p>
 */
@RestController
@RequestMapping("/api/schedules/mine")
public class MyScheduleController {

    private final ScheduleQueryService queryService;

    public MyScheduleController(ScheduleQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 本人某月的已发布排班。
     *
     * <p>{@code staffId} 只取自登录态，不接受前端传入，避免越看别人的班；
     * 科长、大屏账号没有关联人员，{@code staffId} 为 null，此时只有日历、没有班次。</p>
     */
    @GetMapping("")
    public ApiResponse<MineVO> mine(@RequestParam String yearMonth, @AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(queryService.mine(me.staffId(), yearMonth));
    }
}
