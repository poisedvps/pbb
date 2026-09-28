package com.hospital.pbb.cycle;

import com.hospital.pbb.common.ApiResponse;
import com.hospital.pbb.cycle.dto.CycleTemplateRequest;
import com.hospital.pbb.cycle.dto.CycleTemplateVO;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 排班周期模板接口（设计 §8.4）。
 *
 * <p>模板只服务于"按规则生成"，成员和大屏都不读它，所以连查询也只对科长开放，
 * 整个类统一 {@code @PreAuthorize}，无需逐个方法再标一遍。</p>
 */
@RestController
@RequestMapping("/api/cycle-templates")
@PreAuthorize("hasRole('ADMIN')")
public class CycleTemplateController {

    private final CycleTemplateService cycleTemplateService;

    public CycleTemplateController(CycleTemplateService cycleTemplateService) {
        this.cycleTemplateService = cycleTemplateService;
    }

    @GetMapping("")
    public ApiResponse<List<CycleTemplateVO>> list() {
        return ApiResponse.ok(cycleTemplateService.list());
    }

    @PostMapping("")
    public ApiResponse<CycleTemplateVO> create(@Valid @RequestBody CycleTemplateRequest req) {
        return ApiResponse.ok(cycleTemplateService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<CycleTemplateVO> update(@PathVariable Long id, @Valid @RequestBody CycleTemplateRequest req) {
        return ApiResponse.ok(cycleTemplateService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        cycleTemplateService.delete(id);
        return ApiResponse.ok(null);
    }
}
