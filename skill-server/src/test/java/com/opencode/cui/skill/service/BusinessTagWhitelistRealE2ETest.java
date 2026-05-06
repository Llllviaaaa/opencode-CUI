package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.SysConfig;
import com.opencode.cui.skill.repository.SysConfigMapper;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真实 e2e 集成测试：连真实 MySQL + 真实 Redis，端到端验证白名单 gate。
 *
 * <p>与 {@link BusinessTagWhitelistEndToEndTest}（mock IO 版）的区别：
 * <ul>
 *   <li>本测试启动真 SpringBoot context，连本地 dev DB 和 Redis</li>
 *   <li>真实 sys_config 表 + 真实 Flyway schema + 真实 Redis 缓存写入</li>
 *   <li>@Transactional 回滚 DB 改动，@AfterEach 清 Redis 缓存</li>
 * </ul>
 *
 * <p>本测试用唯一前缀 tag（rt_e2e_*）避免污染已存在数据。
 */
@SpringBootTest
@Transactional
class BusinessTagWhitelistRealE2ETest {

    private static final String SWITCH_TYPE = "cloud_route";
    private static final String SWITCH_KEY = "business_whitelist_enabled";
    private static final String WHITELIST_TYPE = "business_cloud_whitelist";

    private static final String CACHE_KEY_SET = "ss:config:set:business_cloud_whitelist";
    private static final String CACHE_KEY_SWITCH = "ss:config:cloud_route:business_whitelist_enabled";

    private static final String TAG_HIT = "rt_e2e_tag_hit";
    private static final String TAG_DISABLED = "rt_e2e_tag_disabled";

    @Autowired AssistantScopeDispatcher dispatcher;
    @Autowired SysConfigMapper sysConfigMapper;
    @Autowired StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        // 清 Redis 缓存（@Transactional 不管 Redis）
        redisTemplate.delete(CACHE_KEY_SET);
        redisTemplate.delete(CACHE_KEY_SWITCH);
    }

    @AfterEach
    void tearDown() {
        // 清 Redis 缓存（DB 改动会被 @Transactional 自动回滚）
        redisTemplate.delete(CACHE_KEY_SET);
        redisTemplate.delete(CACHE_KEY_SWITCH);
    }

    private void setSwitch(String value) {
        SysConfig sw = sysConfigMapper.findByTypeAndKey(SWITCH_TYPE, SWITCH_KEY);
        if (sw == null) {
            sw = new SysConfig();
            sw.setConfigType(SWITCH_TYPE);
            sw.setConfigKey(SWITCH_KEY);
            sw.setConfigValue(value);
            sw.setDescription("RealE2E test switch");
            sw.setStatus(1);
            sw.setSortOrder(0);
            sysConfigMapper.insert(sw);
        } else {
            sw.setConfigValue(value);
            sysConfigMapper.update(sw);
        }
        redisTemplate.delete(CACHE_KEY_SWITCH);
    }

    private void insertTag(String tag, int status) {
        SysConfig c = new SysConfig();
        c.setConfigType(WHITELIST_TYPE);
        c.setConfigKey(tag);
        c.setConfigValue("1");
        c.setDescription("RealE2E test tag");
        c.setStatus(status);
        c.setSortOrder(0);
        sysConfigMapper.insert(c);
        redisTemplate.delete(CACHE_KEY_SET);
    }

    private AssistantInfo businessInfo(String tag) {
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag(tag);
        return info;
    }

    private AssistantInfo personalInfo() {
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("personal");
        return info;
    }

    // ============================ 6 矩阵场景 ============================

    @Test
    @DisplayName("L3.1 switch=0 + business + any tag → cloud (preserves current line behavior)")
    void l3_1_switchOff_businessAnyTag_cloud() {
        setSwitch("0");
        AssistantScopeStrategy s = dispatcher.getStrategy(businessInfo("anything"));
        assertEquals("business", s.getScope());
    }

    @Test
    @DisplayName("L3.2 switch=1 + tag in whitelist → cloud")
    void l3_2_switchOn_tagHit_cloud() {
        setSwitch("1");
        insertTag(TAG_HIT, 1);
        AssistantScopeStrategy s = dispatcher.getStrategy(businessInfo(TAG_HIT));
        assertEquals("business", s.getScope());
    }

    @Test
    @DisplayName("L3.3 switch=1 + tag NOT in whitelist → personal (downgrade)")
    void l3_3_switchOn_tagMiss_personal() {
        setSwitch("1");
        insertTag(TAG_HIT, 1);
        AssistantScopeStrategy s = dispatcher.getStrategy(businessInfo("rt_e2e_tag_unknown"));
        assertEquals("personal", s.getScope());
    }

    @Test
    @DisplayName("L3.4 switch=1 + null businessTag → personal + WARN")
    void l3_4_switchOn_nullTag_personal() {
        setSwitch("1");
        AssistantScopeStrategy s = dispatcher.getStrategy(businessInfo(null));
        assertEquals("personal", s.getScope());
    }

    @Test
    @DisplayName("L3.5 personal info → personal (whitelist not consulted)")
    void l3_5_personal_personal() {
        setSwitch("1");
        AssistantScopeStrategy s = dispatcher.getStrategy(personalInfo());
        assertEquals("personal", s.getScope());
    }

    @Test
    @DisplayName("L3.6 switch=0 + null businessTag → cloud (do NOT downgrade)")
    void l3_6_switchOff_nullTag_cloud() {
        setSwitch("0");
        AssistantScopeStrategy s = dispatcher.getStrategy(businessInfo(null));
        assertEquals("business", s.getScope());
    }

    // ============================ 补充场景 ============================

    @Test
    @DisplayName("L3.7 switch=1 + tag with status=0 → personal (status filter works)")
    void l3_7_switchOn_tagDisabled_personal() {
        setSwitch("1");
        insertTag(TAG_DISABLED, 0);
        AssistantScopeStrategy s = dispatcher.getStrategy(businessInfo(TAG_DISABLED));
        assertEquals("personal", s.getScope());
    }

    @Test
    @DisplayName("L3.8 null AssistantInfo → personal")
    void l3_8_nullInfo_personal() {
        setSwitch("1");
        AssistantScopeStrategy s = dispatcher.getStrategy((AssistantInfo) null);
        assertEquals("personal", s.getScope());
    }

    // ============================ 缓存写回验证 ============================

    @Test
    @DisplayName("L2.2 First lookup writes set cache to Redis (verifies real cache write path)")
    void l2_2_firstLookupWritesSetCache() {
        setSwitch("1");
        insertTag(TAG_HIT, 1);

        // Sanity check: cache should be empty before lookup
        Boolean exists = redisTemplate.hasKey(CACHE_KEY_SET);
        assertEquals(Boolean.FALSE, exists);

        dispatcher.getStrategy(businessInfo(TAG_HIT));

        // After lookup: set cache should exist
        Boolean afterExists = redisTemplate.hasKey(CACHE_KEY_SET);
        assertEquals(Boolean.TRUE, afterExists);

        String cached = redisTemplate.opsForValue().get(CACHE_KEY_SET);
        // 缓存内容包含我们插入的 tag
        org.junit.jupiter.api.Assertions.assertTrue(cached.contains(TAG_HIT),
                "Set cache should contain the inserted tag, got: " + cached);
    }
}
