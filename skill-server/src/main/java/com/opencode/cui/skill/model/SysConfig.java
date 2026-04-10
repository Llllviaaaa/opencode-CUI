package com.opencode.cui.skill.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统通用配置实体。
 * 对应数据库 sys_config 表，用于存储可动态调整的系统级 key-value 配置。
 *
 * <p>
 * 配置通过 configType 分组，configKey 在同一 type 下唯一。
 * status=1 表示启用，status=0 表示禁用；只有启用的配置才会被业务逻辑读取。
 * </p>
 */
@Data
public class SysConfig {

    /** 主键 */
    private Long id;

    /** 配置类型（分组标识，如 SYSTEM、FEATURE_FLAG 等） */
    private String configType;

    /** 配置键 */
    private String configKey;

    /** 配置值 */
    private String configValue;

    /** 配置说明 */
    private String description;

    /**
     * 状态：1=启用，0=禁用。
     * 仅 status=1 的配置会被 getValue() 读取并缓存。
     */
    private Integer status;

    /** 排序权重，数值越小越靠前 */
    private Integer sortOrder;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    private LocalDateTime updatedAt;
}
