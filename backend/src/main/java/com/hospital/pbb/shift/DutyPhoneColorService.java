package com.hospital.pbb.shift;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.shift.dto.DutyPhoneColorVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 值班电话底色设置（设计 §5、§8.3 app_setting）。
 *
 * <p>全系统只有一行 {@link AppSetting#DUTY_PHONE_COLOR}，读的时候没有这一行就回默认值，
 * 写的时候没有则新建，所以迁移脚本里预置的那一行即使被误删接口也不会坏。</p>
 */
@Service
public class DutyPhoneColorService {

    /** 与 shift_type.color 同一套写法：# 加 6 位十六进制，大小写都收，入库前统一转小写 */
    private static final Pattern COLOR_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /** 操作日志的 target，全系统只有这一项设置，写死即可 */
    private static final String TARGET = "值班电话底色";

    private final AppSettingRepository settingRepo;
    private final OpLogService opLog;
    private final Clock clock;

    public DutyPhoneColorService(AppSettingRepository settingRepo, OpLogService opLog, Clock clock) {
        this.settingRepo = settingRepo;
        this.opLog = opLog;
        this.clock = clock;
    }

    /** 当前底色；库里没有这一行时返回 {@link AppSetting#DEFAULT_DUTY_PHONE_COLOR} */
    public DutyPhoneColorVO get() {
        return new DutyPhoneColorVO(settingRepo.findById(AppSetting.DUTY_PHONE_COLOR)
                .map(AppSetting::getValue)
                .orElse(AppSetting.DEFAULT_DUTY_PHONE_COLOR));
    }

    /**
     * 修改底色，保存的是小写形式。
     *
     * @throws BizException 1304 颜色格式应为 #RRGGBB
     */
    @Transactional
    public DutyPhoneColorVO update(String color) {
        if (color == null || !COLOR_PATTERN.matcher(color).matches()) {
            throw new BizException(1304, "颜色格式应为 #RRGGBB");
        }
        // 只查一次：旧值进日志，没有这一行时就地新建
        Optional<AppSetting> found = settingRepo.findById(AppSetting.DUTY_PHONE_COLOR);
        String oldValue = found.map(AppSetting::getValue).orElse(AppSetting.DEFAULT_DUTY_PHONE_COLOR);
        String newValue = color.toLowerCase(Locale.ROOT);

        AppSetting setting = found.orElseGet(() -> {
            AppSetting created = new AppSetting();
            created.setKey(AppSetting.DUTY_PHONE_COLOR);
            return created;
        });
        setting.setValue(newValue);
        setting.setUpdatedAt(OffsetDateTime.now(clock));
        settingRepo.save(setting);

        opLog.record(OpAction.UPDATE_DUTY_PHONE_COLOR, TARGET, oldValue + " → " + newValue);
        return new DutyPhoneColorVO(newValue);
    }
}
