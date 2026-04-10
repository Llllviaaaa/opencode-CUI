package com.opencode.cui.skill.mapper;

import com.opencode.cui.skill.model.SysConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SysConfig 的 MyBatis Mapper。
 * 对应数据库 sys_config 表，提供基础 CRUD 操作。
 */
@Mapper
public interface SysConfigMapper {

    /**
     * 按 configType + configKey 查询单条记录（不过滤 status）。
     *
     * @param configType 配置类型
     * @param configKey  配置键
     * @return 匹配的配置，若不存在则返回 null
     */
    SysConfig findByTypeAndKey(@Param("configType") String configType,
                               @Param("configKey") String configKey);

    /**
     * 按 configType 查询所有配置，按 sort_order 升序排列。
     *
     * @param configType 配置类型
     * @return 配置列表
     */
    List<SysConfig> findByType(@Param("configType") String configType);

    /**
     * 按主键查询。
     *
     * @param id 主键
     * @return 配置，若不存在则返回 null
     */
    SysConfig findById(@Param("id") Long id);

    /**
     * 插入新配置。
     *
     * @param config 待插入的配置（id 可为 null，由 DB 自增）
     * @return 影响行数
     */
    int insert(SysConfig config);

    /**
     * 更新配置（按 id）。
     *
     * @param config 待更新的配置
     * @return 影响行数
     */
    int update(SysConfig config);

    /**
     * 按主键删除配置。
     *
     * @param id 主键
     * @return 影响行数
     */
    int deleteById(@Param("id") Long id);
}
