package com.hospital.pbb.shift;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 系统设置项（设计 §8.3 app_setting），键值对，一行一项。
 *
 * <p>目前只有“值班电话底色”一项，由 shift 模块唯一写入（见 {@link AppSettingRepository}）。
 * 新增设置项时在这里加 key 常量，并在 {@code V2__cycle_template_duty_phone.sql} 里预置一行。</p>
 */
@Entity
@Table(name = "app_setting")
public class AppSetting {

    /** 值班电话整格底色对应的 key */
    public static final String DUTY_PHONE_COLOR = "duty_phone_color";

    /** 值班电话底色默认值（黄色） */
    public static final String DEFAULT_DUTY_PHONE_COLOR = "#fde047";

    @Id
    @Column(name = "setting_key", length = 64)
    private String key;

    @Column(name = "setting_value", nullable = false, length = 200)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
