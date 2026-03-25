package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.SessionRoute;
import com.opencode.cui.skill.repository.SessionRouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/** SessionRouteService 单元测试：验证会话与 Gateway 实例的路由绑定和查询。 */
class SessionRouteServiceTest {

    @Mock
    private SessionRouteRepository repository;

    @Mock
    private StringRedisTemplate redisTemplate;

    private SessionRouteService service;

    private static final String INSTANCE_ID = "ss-az1-1";

    @BeforeEach
    void setUp() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new SessionRouteService(repository, redisTemplate, INSTANCE_ID, 1800);
    }

    @Nested
    @DisplayName("路由记录 CRUD")
    class CrudTests {

        @Test
        @DisplayName("createRoute 插入记录并返回")
        void createRouteInsertsRecord() {
            service.createRoute("ak-1", 12345L, "skill-server", "user-123");

            ArgumentCaptor<SessionRoute> captor = ArgumentCaptor.forClass(SessionRoute.class);
            verify(repository).insert(captor.capture());

            SessionRoute route = captor.getValue();
            assertEquals("ak-1", route.getAk());
            assertEquals(12345L, route.getWelinkSessionId());
            assertEquals("skill-server", route.getSourceType());
            assertEquals(INSTANCE_ID, route.getSourceInstance());
            assertEquals("user-123", route.getUserId());
            assertEquals("ACTIVE", route.getStatus());
            assertNull(route.getToolSessionId());
        }

        @Test
        @DisplayName("updateToolSessionId 更新记录")
        void updateToolSessionIdUpdatesRecord() {
            service.updateToolSessionId(12345L, "skill-server", "oc-uuid-001");

            verify(repository).updateToolSessionId(12345L, "skill-server", "oc-uuid-001");
        }

        @Test
        @DisplayName("closeRoute 设置状态为 CLOSED")
        void closeRouteSetsStatusClosed() {
            service.closeRoute(12345L, "skill-server");

            verify(repository).updateStatus(12345L, "skill-server", "CLOSED");
        }
    }

    @Nested
    @DisplayName("Ownership 检查")
    class OwnershipTests {

        @Test
        @DisplayName("isMySession 返回 true 当 sourceInstance 匹配本实例")
        void isMySessionReturnsTrueWhenMatching() {
            SessionRoute route = new SessionRoute();
            route.setSourceInstance(INSTANCE_ID);
            when(repository.findByWelinkSessionId(12345L)).thenReturn(route);

            assertTrue(service.isMySession("12345"));
        }

        @Test
        @DisplayName("isMySession 返回 false 当 sourceInstance 不匹配")
        void isMySessionReturnsFalseWhenNotMatching() {
            SessionRoute route = new SessionRoute();
            route.setSourceInstance("ss-az1-2");
            when(repository.findByWelinkSessionId(12345L)).thenReturn(route);

            assertFalse(service.isMySession("12345"));
        }

        @Test
        @DisplayName("isMySession 返回 false 当记录不存在")
        void isMySessionReturnsFalseWhenNotFound() {
            when(repository.findByWelinkSessionId(12345L)).thenReturn(null);

            assertFalse(service.isMySession("12345"));
        }

        @Test
        @DisplayName("isMyToolSession 返回 true 当 sourceInstance 匹配")
        void isMyToolSessionReturnsTrueWhenMatching() {
            SessionRoute route = new SessionRoute();
            route.setSourceInstance(INSTANCE_ID);
            when(repository.findByToolSessionId("oc-uuid-001")).thenReturn(route);

            assertTrue(service.isMyToolSession("oc-uuid-001"));
        }

        @Test
        @DisplayName("isMyToolSession 返回 false 当不匹配或不存在")
        void isMyToolSessionReturnsFalseWhenNotMatching() {
            when(repository.findByToolSessionId("oc-uuid-001")).thenReturn(null);

            assertFalse(service.isMyToolSession("oc-uuid-001"));
        }

        @Test
        @DisplayName("isMySession DB 异常时降级返回 true")
        void isMySessionReturnsTrueOnDbException() {
            when(repository.findByWelinkSessionId(12345L)).thenThrow(new RuntimeException("DB connection lost"));

            assertTrue(service.isMySession("12345"));
        }

        @Test
        @DisplayName("isMyToolSession DB 异常时降级返回 true")
        void isMyToolSessionReturnsTrueOnDbException() {
            when(repository.findByToolSessionId("oc-uuid-001")).thenThrow(new RuntimeException("DB connection lost"));

            assertTrue(service.isMyToolSession("oc-uuid-001"));
        }
    }

    @Nested
    @DisplayName("查询")
    class QueryTests {

        @Test
        @DisplayName("findByToolSessionId 返回路由记录")
        void findByToolSessionIdReturnsRoute() {
            SessionRoute expected = new SessionRoute();
            expected.setToolSessionId("oc-uuid-001");
            expected.setSourceInstance(INSTANCE_ID);
            when(repository.findByToolSessionId("oc-uuid-001")).thenReturn(expected);

            SessionRoute result = service.findByToolSessionId("oc-uuid-001");

            assertNotNull(result);
            assertEquals("oc-uuid-001", result.getToolSessionId());
        }

        @Test
        @DisplayName("findByToolSessionId null 时返回 null")
        void findByToolSessionIdReturnsNullForNull() {
            assertNull(service.findByToolSessionId(null));
        }
    }

    @Nested
    @DisplayName("启动接管与优雅关闭")
    class LifecycleTests {

        @Test
        @DisplayName("takeoverActiveRoutes 将指定 AK 下所有 ACTIVE 路由的 sourceInstance 更新为当前实例")
        void takeoverActiveRoutesUpdatesSourceInstance() {
            service.takeoverActiveRoutes("ak-1");

            verify(repository).takeoverByAk("ak-1", INSTANCE_ID);
        }

        @Test
        @DisplayName("closeAllByInstance 关闭当前实例所有 ACTIVE 路由")
        void closeAllByInstanceClosesAllActiveRoutes() {
            service.closeAllByInstance();

            verify(repository).closeAllBySourceInstance(INSTANCE_ID);
        }
    }

    @Nested
    @DisplayName("清理任务")
    class CleanupTests {

        @Test
        @DisplayName("cleanupStaleRoutes 关闭超时的 ACTIVE 僵尸记录")
        void cleanupStaleRoutesClosesZombies() {
            when(repository.closeStaleActiveRoutes(any())).thenReturn(3);
            when(repository.purgeClosedBefore(any())).thenReturn(10);

            service.cleanupStaleRoutes(24, 7);

            verify(repository).closeStaleActiveRoutes(any());
            verify(repository).purgeClosedBefore(any());
        }
    }

    @Nested
    @DisplayName("并发防护")
    class ConcurrencyTests {

        @Test
        @DisplayName("createRoute 重复插入时不抛异常")
        void createRouteDuplicateDoesNotThrow() {
            when(repository.insert(any())).thenThrow(
                    new org.springframework.dao.DuplicateKeyException("Duplicate entry"));

            // 不应抛异常
            service.createRoute("ak-1", 12345L, "skill-server", "user-123");
        }
    }
}
