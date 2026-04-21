package com.opencode.cui.skill.controller;

import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.SysConfig;
import com.opencode.cui.skill.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统配置管理接口。
 * 提供按类型查询、新增、修改、删除配置的 RESTful 接口。
 *
 * <p>所有接口路径前缀：{@code /api/admin/configs}</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/configs")
@RequiredArgsConstructor
public class SysConfigController {

    private final SysConfigService sysConfigService;

    /**
     * GET /api/admin/configs?type={configType}
     * 按配置类型查询配置列表，按 sort_order 升序排列。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<SysConfig>>> listByType(
            @RequestParam("type") String configType) {
        log.info("[ENTRY] listByType: configType={}", configType);
        List<SysConfig> configs = sysConfigService.listByType(configType);
        return ResponseEntity.ok(ApiResponse.ok(configs));
    }

    /**
     * POST /api/admin/configs
     * 新增系统配置。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> create(@RequestBody SysConfig config) {
        log.info("[ENTRY] create: configType={}, configKey={}", config.getConfigType(), config.getConfigKey());
        sysConfigService.create(config);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /**
     * PUT /api/admin/configs/{id}
     * 修改系统配置（按 id）。
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> update(
            @PathVariable Long id,
            @RequestBody SysConfig config) {
        log.info("[ENTRY] update: id={}", id);
        config.setId(id);
        sysConfigService.update(config);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /**
     * DELETE /api/admin/configs/{id}
     * 删除系统配置（按 id）。缓存依赖自然过期。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        log.info("[ENTRY] delete: id={}", id);
        sysConfigService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
