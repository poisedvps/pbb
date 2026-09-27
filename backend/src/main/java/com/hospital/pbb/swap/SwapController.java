package com.hospital.pbb.swap;

import com.hospital.pbb.common.ApiResponse;
import com.hospital.pbb.swap.dto.CreateSwapRequest;
import com.hospital.pbb.swap.dto.ReviewRequest;
import com.hospital.pbb.swap.dto.SwapVO;
import com.hospital.pbb.user.AuthUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 调班申请接口（任务单 M3-03 列表、M3-04 发起、M3-05 确认/拒绝/撤销/审批）。
 *
 * <p>调班是科室内部的事，大屏账号用不上，所以类上直接限 ADMIN、MEMBER；
 * 谁是当前用户只从登录态取，不接受前端传 {@code staffId}。</p>
 */
@RestController
@RequestMapping("/api/swaps")
@PreAuthorize("hasAnyRole('ADMIN','MEMBER')")
public class SwapController {

    private final SwapService swapService;

    public SwapController(SwapService swapService) {
        this.swapService = swapService;
    }

    /** 调班列表，{@code scope} 取 ALL / MINE / TODO，不传按 ALL */
    @GetMapping("")
    public ApiResponse<List<SwapVO>> list(@RequestParam(required = false) String scope,
                                          @AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(swapService.list(me, scope));
    }

    /** 发起调班申请（任务单 M3-04），申请人取登录态，不接收请求体里的人员 id */
    @PostMapping("")
    public ApiResponse<SwapVO> create(@Valid @RequestBody CreateSwapRequest req,
                                      @AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(swapService.create(req, me));
    }

    /** 对方同意（任务单 M3-05），谁是对方取登录态里的 {@code staffId}，不允许前端传 id */
    @PostMapping("/{id}/confirm")
    public ApiResponse<SwapVO> confirm(@PathVariable Long id, @AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(swapService.confirm(id, me));
    }

    /** 对方拒绝，申请直接结束，不再进入科长审批 */
    @PostMapping("/{id}/reject-peer")
    public ApiResponse<SwapVO> rejectPeer(@PathVariable Long id, @AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(swapService.rejectPeer(id, me));
    }

    /** 申请人撤销（两个待处理状态下都可） */
    @PostMapping("/{id}/cancel")
    public ApiResponse<SwapVO> cancel(@PathVariable Long id, @AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(swapService.cancel(id, me));
    }

    /**
     * 科长审批通过，同时回写排班。
     *
     * <p>审批意见是可选的，所以整个 body 也可以不传（{@code required = false}）；
     * 方法上的 {@code @PreAuthorize} 覆盖类上的 ADMIN/MEMBER，成员进不了这两个接口。</p>
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<SwapVO> approve(@PathVariable Long id, @Valid @RequestBody(required = false) ReviewRequest req,
                                       @AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(swapService.approve(id, req == null ? null : req.comment(), me));
    }

    /** 科长驳回，不动排班 */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<SwapVO> reject(@PathVariable Long id, @Valid @RequestBody(required = false) ReviewRequest req,
                                      @AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(swapService.reject(id, req == null ? null : req.comment(), me));
    }
}
