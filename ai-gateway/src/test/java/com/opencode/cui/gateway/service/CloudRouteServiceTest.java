package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.CloudRouteInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CloudRouteService 单元测试（TDD）。
 *
 * <ul>
 *   <li>缓存命中直接返回</li>
 *   <li>缓存未命中调上游 API 写缓存</li>
 *   <li>上游 API 失败返回 null</li>
 *   <li>正确解析 appId/endpoint/protocol/authType</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CloudRouteServiceTest {

    private static final String TEST_AK = "ak-test-001";
    private static final String CACHE_KEY = "gw:cloud:route:" + TEST_AK;

    private static final String UPSTREAM_JSON = """
            {"code":"200","data":{"identityType":"3","hisAppId":"app_36209",\
            "endpoint":"https://cloud.example.com/chat","protocol":"sse","authType":"soa"}}
            """;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    /** 使用 Spy 以便在测试中 mock protected 方法 fetchFromUpstream */
    private CloudRouteService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service = spy(new CloudRouteService(
                redisTemplate,
                new ObjectMapper(),
                "https://api.example.com",
                "test-bearer-token",
                300L));
    }

    // -----------------------------------------------------------------------
    // 1. 缓存命中直接返回
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("缓存命中场景")
    class CacheHitTests {

        @Test
        @DisplayName("Redis 缓存命中时直接返回，不调用上游 API")
        void shouldReturnCachedRouteWhenCacheHit() throws Exception {
            // Arrange: Redis 返回已缓存的 JSON
            String cachedJson = """
                    {"appId":"app_36209","endpoint":"https://cloud.example.com/chat",\
                    "protocol":"sse","authType":"soa"}
                    """;
            when(valueOps.get(CACHE_KEY)).thenReturn(cachedJson);

            // Act
            CloudRouteInfo result = service.getRouteInfo(TEST_AK);

            // Assert
            assertNotNull(result);
            assertEquals("app_36209", result.getAppId());
            assertEquals("https://cloud.example.com/chat", result.getEndpoint());
            assertEquals("sse", result.getProtocol());
            assertEquals("soa", result.getAuthType());

            // 不应调用上游 API
            verify(service, never()).fetchFromUpstream(anyString());
            // 不应写缓存
            verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
        }
    }

    // -----------------------------------------------------------------------
    // 2. 缓存未命中，调上游 API，写缓存
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("缓存未命中场景")
    class CacheMissTests {

        @Test
        @DisplayName("缓存未命中时调用上游 API 并将结果写入缓存")
        void shouldFetchFromUpstreamAndCacheOnMiss() throws Exception {
            // Arrange: Redis miss
            when(valueOps.get(CACHE_KEY)).thenReturn(null);
            // mock protected 方法返回上游原始 JSON
            doReturn(UPSTREAM_JSON).when(service).fetchFromUpstream(TEST_AK);

            // Act
            CloudRouteInfo result = service.getRouteInfo(TEST_AK);

            // Assert: 正确解析
            assertNotNull(result);
            assertEquals("app_36209", result.getAppId());
            assertEquals("https://cloud.example.com/chat", result.getEndpoint());
            assertEquals("sse", result.getProtocol());
            assertEquals("soa", result.getAuthType());

            // 应调用上游 API 一次
            verify(service, times(1)).fetchFromUpstream(TEST_AK);
            // 应写入 Redis 缓存
            verify(valueOps, times(1)).set(eq(CACHE_KEY), anyString(), eq(300L), eq(TimeUnit.SECONDS));
        }
    }

    // -----------------------------------------------------------------------
    // 3. 上游 API 失败返回 null
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("上游 API 失败场景")
    class UpstreamFailureTests {

        @Test
        @DisplayName("上游 API 抛出异常时返回 null，不写缓存")
        void shouldReturnNullWhenUpstreamThrows() throws Exception {
            // Arrange
            when(valueOps.get(CACHE_KEY)).thenReturn(null);
            doThrow(new RuntimeException("Connection refused")).when(service).fetchFromUpstream(TEST_AK);

            // Act
            CloudRouteInfo result = service.getRouteInfo(TEST_AK);

            // Assert
            assertNull(result);
            // 不应写缓存
            verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
        }

        @Test
        @DisplayName("上游 API 返回 null 时返回 null，不写缓存")
        void shouldReturnNullWhenUpstreamReturnsNull() throws Exception {
            // Arrange
            when(valueOps.get(CACHE_KEY)).thenReturn(null);
            doReturn(null).when(service).fetchFromUpstream(TEST_AK);

            // Act
            CloudRouteInfo result = service.getRouteInfo(TEST_AK);

            // Assert
            assertNull(result);
            verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
        }
    }

    // -----------------------------------------------------------------------
    // 4. 正确解析 appId/endpoint/protocol/authType
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("响应解析场景")
    class ParseTests {

        @Test
        @DisplayName("正确解析上游 API 响应中 data 节点的所有字段")
        void shouldParseAllFieldsFromUpstreamResponse() throws Exception {
            // Arrange
            when(valueOps.get(CACHE_KEY)).thenReturn(null);
            String responseJson = """
                    {"code":"200","data":{"identityType":"3","hisAppId":"app_99999",\
                    "endpoint":"https://ws.example.com/stream","protocol":"websocket","authType":"apig"}}
                    """;
            doReturn(responseJson).when(service).fetchFromUpstream(TEST_AK);

            // Act
            CloudRouteInfo result = service.getRouteInfo(TEST_AK);

            // Assert: 全字段校验
            assertNotNull(result);
            assertEquals("app_99999", result.getAppId());
            assertEquals("https://ws.example.com/stream", result.getEndpoint());
            assertEquals("websocket", result.getProtocol());
            assertEquals("apig", result.getAuthType());
        }

        @Test
        @DisplayName("上游响应 code 非 200 时返回 null")
        void shouldReturnNullWhenUpstreamCodeIsNot200() throws Exception {
            // Arrange
            when(valueOps.get(CACHE_KEY)).thenReturn(null);
            String errorJson = """
                    {"code":"500","message":"Internal Server Error"}
                    """;
            doReturn(errorJson).when(service).fetchFromUpstream(TEST_AK);

            // Act
            CloudRouteInfo result = service.getRouteInfo(TEST_AK);

            // Assert
            assertNull(result);
            verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
        }
    }
}
