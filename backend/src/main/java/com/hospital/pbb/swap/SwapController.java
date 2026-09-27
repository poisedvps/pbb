package com.hospital.pbb.swap;

import com.hospital.pbb.common.ApiResponse;
import com.hospital.pbb.swap.dto.CreateSwapRequest;
import com.hospital.pbb.swap.dto.SwapVO;
import com.hospital.pbb.user.AuthUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 调班申请接口（任务单 M3-03）。
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
}
