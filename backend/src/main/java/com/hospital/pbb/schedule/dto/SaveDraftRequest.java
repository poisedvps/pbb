package com.hospital.pbb.schedule.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 暂存一次提交的全部内容（任务单 M4-09，设计 §8.5“暂存”）。
 *
 * <p>整月最多 6 周（跨月那一周也算进来），所以 {@code dutyPhones} 上限 6；
 * {@code entries} 上限 2000 是防手滑的上限，正常一次整月也就几百格。</p>
 *
 * <p>两条列表都可以是空列表（只改了值班电话或只改了格子），但不能是 {@code null}。</p>
 *
 * @param entries    改过的格子，写法与单格保存完全一致
 * @param dutyPhones 改过的值班电话周，{@code staffId=null} 表示清除该周
 */
public record SaveDraftRequest(@NotNull @Size(max = 2000) List<@Valid UpdateEntryRequest> entries,
                               @NotNull @Size(max = 6) List<@Valid DutyPhoneChange> dutyPhones) {}
