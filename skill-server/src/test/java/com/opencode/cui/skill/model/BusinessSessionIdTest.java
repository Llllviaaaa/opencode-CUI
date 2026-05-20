package com.opencode.cui.skill.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BusinessSessionIdTest {

    @Test
    @DisplayName("parse: null → empty")
    void parseNullReturnsEmpty() {
        assertTrue(BusinessSessionId.parse(null).isEmpty());
    }

    @Test
    @DisplayName("parse: blank → empty")
    void parseBlankReturnsEmpty() {
        assertTrue(BusinessSessionId.parse("").isEmpty());
        assertTrue(BusinessSessionId.parse("   ").isEmpty());
    }

    @Test
    @DisplayName("parse: 合法 group_<id>_<sender>")
    void parseGroupHappy() {
        Optional<BusinessSessionId> parsed = BusinessSessionId.parse("group_g123_u456");
        assertTrue(parsed.isPresent());
        BusinessSessionId b = parsed.get();
        assertEquals(BusinessSessionId.TargetType.GROUP, b.targetType());
        assertEquals("g123", b.targetId());
        assertEquals("u456", b.senderAccount());
    }

    @Test
    @DisplayName("parse: 合法 direct_<id>_<sender>")
    void parseDirectHappy() {
        Optional<BusinessSessionId> parsed = BusinessSessionId.parse("direct_t789_u456");
        assertTrue(parsed.isPresent());
        BusinessSessionId b = parsed.get();
        assertEquals(BusinessSessionId.TargetType.DIRECT, b.targetType());
        assertEquals("t789", b.targetId());
        assertEquals("u456", b.senderAccount());
    }

    @Test
    @DisplayName("parse: 段数 2（缺末段）→ empty")
    void parseTwoSegmentsReturnsEmpty() {
        assertTrue(BusinessSessionId.parse("group_g123").isEmpty());
    }

    @Test
    @DisplayName("parse: 段数 4（多下划线）→ empty")
    void parseFourSegmentsReturnsEmpty() {
        assertTrue(BusinessSessionId.parse("group_g_1_2").isEmpty());
        assertTrue(BusinessSessionId.parse("direct_t_x_y").isEmpty());
    }

    @Test
    @DisplayName("parse: 未知前缀 → empty")
    void parseUnknownPrefixReturnsEmpty() {
        assertTrue(BusinessSessionId.parse("chat_g123_u456").isEmpty());
        assertTrue(BusinessSessionId.parse("g123_u456").isEmpty());
        assertTrue(BusinessSessionId.parse("foo").isEmpty());
    }

    @Test
    @DisplayName("parse: 段数 3 但中段或末段空 → empty")
    void parseEmptySegmentReturnsEmpty() {
        assertTrue(BusinessSessionId.parse("group__u456").isEmpty());
        assertTrue(BusinessSessionId.parse("direct_t789_").isEmpty());
    }
}
