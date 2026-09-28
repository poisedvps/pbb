package com.hospital.pbb.cycle.dto;

import com.hospital.pbb.cycle.CycleTemplate;

import java.util.List;

/**
 * 排班周期模板的出参。
 *
 * <p>{@code days} 是周一..周日 7 个班次代号，下标 0 = 周一，与库里的 day1..day7 一一对应。</p>
 */
public record CycleTemplateVO(Long id, String name, List<String> days, boolean isDefault) {

    public static CycleTemplateVO of(CycleTemplate t) {
        return new CycleTemplateVO(t.getId(), t.getName(), t.days(), t.isDefaultTemplate());
    }
}
