package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AllowedSlashCommandsResolver} 单元测试。
 *
 * <p>覆盖 PRD v3 AC：
 * <ul>
 *   <li>AC1 正常配置 → list</li>
 *   <li>AC3 null / blank → null</li>
 *   <li>AC4 parse 失败 / 非数组 → null + WARN</li>
 *   <li>AC5 空数组 / 仅 blank 元素 → null</li>
 *   <li>AC6 入参 blank → 不查 sysconfig + 不写 WARN</li>
 *   <li>AC12 数组含非 textual 元素（数字 / 布尔 / 对象 / null）→ null + WARN（严格拒绝）</li>
 *   <li>sysconfig 抛 RuntimeException → null + WARN</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AllowedSlashCommandsResolver")
class AllowedSlashCommandsResolverTest {

    @Mock
    private SysConfigService sysConfigService;

    private AllowedSlashCommandsResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AllowedSlashCommandsResolver(sysConfigService, new ObjectMapper());
    }

    // ==================== AC1 / 正常路径 ====================

    @Test
    @DisplayName("正常 JSON 数组 → 返回 list（顺序一致）")
    void resolve_normalJsonArray_returnsList() {
        lenient().when(sysConfigService.getValue("allowed_slash_commands", "im_group"))
                .thenReturn("[\"plan\",\"ask\",\"run\"]");

        List<String> result = resolver.resolve("im", "group");

        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals("plan", result.get(0));
        assertEquals("ask", result.get(1));
        assertEquals("run", result.get(2));
    }

    @Test
    @DisplayName("数组含空白字符串 → 剔除 blank 后返回剩余元素")
    void resolve_arrayWithBlankStrings_filtersBlank() {
        lenient().when(sysConfigService.getValue("allowed_slash_commands", "im_direct"))
                .thenReturn("[\"plan\",\"\",\"  \",\"ask\"]");

        List<String> result = resolver.resolve("im", "direct");

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("plan", result.get(0));
        assertEquals("ask", result.get(1));
    }

    // ==================== AC3 / 未配置 ====================

    @Test
    @DisplayName("AC3 sysconfig 返 null → 返回 null（未配置）")
    void resolve_sysconfigValueNull_returnsNull() {
        lenient().when(sysConfigService.getValue(eq("allowed_slash_commands"), eq("im_group")))
                .thenReturn(null);

        List<String> result = resolver.resolve("im", "group");

        assertNull(result);
    }

    @Test
    @DisplayName("AC3 sysconfig 返 blank → 返回 null")
    void resolve_sysconfigValueBlank_returnsNull() {
        lenient().when(sysConfigService.getValue(eq("allowed_slash_commands"), eq("im_group")))
                .thenReturn("   ");

        List<String> result = resolver.resolve("im", "group");

        assertNull(result);
    }

    // ==================== AC4 / parse 失败 + 非数组 ====================

    @Test
    @DisplayName("AC4 JSON parse 失败 → 返回 null + WARN（不抛异常）")
    void resolve_invalidJson_returnsNull() {
        lenient().when(sysConfigService.getValue(eq("allowed_slash_commands"), eq("im_group")))
                .thenReturn("not-a-json");

        List<String> result = resolver.resolve("im", "group");

        assertNull(result);
    }

    @Test
    @DisplayName("AC4 sysconfig 抛 RuntimeException → 返回 null + WARN")
    void resolve_sysconfigThrowsRuntime_returnsNull() {
        lenient().when(sysConfigService.getValue(eq("allowed_slash_commands"), eq("im_group")))
                .thenThrow(new RuntimeException("simulated redis failure"));

        List<String> result = resolver.resolve("im", "group");

        assertNull(result);
    }

    @Test
    @DisplayName("AC4 JSON 是对象（非数组）→ 返回 null + WARN")
    void resolve_jsonObject_returnsNull() {
        lenient().when(sysConfigService.getValue(eq("allowed_slash_commands"), eq("im_group")))
                .thenReturn("{\"key\":\"value\"}");

        List<String> result = resolver.resolve("im", "group");

        assertNull(result);
    }

    @Test
    @DisplayName("AC4 JSON 是数字（非数组）→ 返回 null + WARN")
    void resolve_jsonNumber_returnsNull() {
        lenient().when(sysConfigService.getValue(eq("allowed_slash_commands"), eq("im_group")))
                .thenReturn("42");

        List<String> result = resolver.resolve("im", "group");

        assertNull(result);
    }

    // ==================== AC5 / 空数组 ====================

    @Test
    @DisplayName("AC5 空数组 → 归一为 null")
    void resolve_emptyArray_returnsNull() {
        lenient().when(sysConfigService.getValue(eq("allowed_slash_commands"), eq("im_group")))
                .thenReturn("[]");

        List<String> result = resolver.resolve("im", "group");

        assertNull(result);
    }

    @Test
    @DisplayName("AC5 数组全 blank 元素 → 归一为 null")
    void resolve_arrayAllBlankElements_returnsNull() {
        lenient().when(sysConfigService.getValue(eq("allowed_slash_commands"), eq("im_group")))
                .thenReturn("[\"\",\"  \",\"\\t\"]");

        List<String> result = resolver.resolve("im", "group");

        assertNull(result);
    }

    // ==================== AC6 / 入参 blank ====================

    @Test
    @DisplayName("AC6 domain null → 不查 sysconfig，不写 WARN")
    void resolve_domainNull_skipsSysconfig() {
        List<String> result = resolver.resolve(null, "group");

        assertNull(result);
        verify(sysConfigService, never()).getValue(eq("allowed_slash_commands"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("AC6 domain blank → 不查 sysconfig")
    void resolve_domainBlank_skipsSysconfig() {
        List<String> result = resolver.resolve("  ", "group");

        assertNull(result);
        verify(sysConfigService, never()).getValue(eq("allowed_slash_commands"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("AC6 type null → 不查 sysconfig")
    void resolve_typeNull_skipsSysconfig() {
        List<String> result = resolver.resolve("im", null);

        assertNull(result);
        verify(sysConfigService, never()).getValue(eq("allowed_slash_commands"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("AC6 type blank → 不查 sysconfig")
    void resolve_typeBlank_skipsSysconfig() {
        List<String> result = resolver.resolve("im", "");

        assertNull(result);
        verify(sysConfigService, never()).getValue(eq("allowed_slash_commands"), org.mockito.ArgumentMatchers.anyString());
    }

    // ==================== AC12 / 严格 string[] 校验：含非 textual 元素整数组拒绝 ====================

    @Test
    @DisplayName("AC12 数组含数字元素 → 返回 null + WARN（严格拒绝，不混合）")
    void resolve_arrayWithNumericElement_returnsNull() {
        lenient().when(sysConfigService.getValue(eq("allowed_slash_commands"), eq("im_group")))
                .thenReturn("[\"plan\",1,\"ask\"]");

        List<String> result = resolver.resolve("im", "group");

        assertNull(result);
    }

    @Test
    @DisplayName("AC12 数组含布尔元素 → 返回 null + WARN")
    void resolve_arrayWithBooleanElement_returnsNull() {
        lenient().when(sysConfigService.getValue(eq("allowed_slash_commands"), eq("im_group")))
                .thenReturn("[true,\"ask\"]");

        List<String> result = resolver.resolve("im", "group");

        assertNull(result);
    }

    @Test
    @DisplayName("AC12 数组含对象元素 → 返回 null + WARN")
    void resolve_arrayWithObjectElement_returnsNull() {
        lenient().when(sysConfigService.getValue(eq("allowed_slash_commands"), eq("im_group")))
                .thenReturn("[\"plan\",{\"k\":\"v\"}]");

        List<String> result = resolver.resolve("im", "group");

        assertNull(result);
    }

    @Test
    @DisplayName("AC12 数组含 null 元素 → 返回 null + WARN")
    void resolve_arrayWithNullElement_returnsNull() {
        lenient().when(sysConfigService.getValue(eq("allowed_slash_commands"), eq("im_group")))
                .thenReturn("[null,\"ask\"]");

        List<String> result = resolver.resolve("im", "group");

        assertNull(result);
    }

    // ==================== configKey 拼接验证 ====================

    @Test
    @DisplayName("configKey 用下划线拼接 domain + type")
    void resolve_configKeyUsesUnderscoreSeparator() {
        lenient().when(sysConfigService.getValue("allowed_slash_commands", "external_direct"))
                .thenReturn("[\"plan\"]");

        List<String> result = resolver.resolve("external", "direct");

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("plan", result.get(0));
        verify(sysConfigService).getValue("allowed_slash_commands", "external_direct");
    }
}
