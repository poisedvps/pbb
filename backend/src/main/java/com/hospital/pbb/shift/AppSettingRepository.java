package com.hospital.pbb.shift;

import org.springframework.data.jpa.repository.JpaRepository;

/** 系统设置仓库：只有 shift 模块可以写（设计 §8.2 模块边界）。 */
public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
