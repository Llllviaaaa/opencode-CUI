package com.opencode.cui.skill.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InvokeCommand 兼容构造器测试（PR2 收口）。
 *
 * <p>验证 PRD AC §C "InvokeCommand 兼容构造器"：
 * 5/6 参数构造器仍能用、新增的 domain/domainType 字段默认 null，
 * 老 caller 不传值时行为不变。</p>
 */
@DisplayName("InvokeCommand")
class InvokeCommandTest {

    @Test
    @DisplayName("5 参数构造器：suppressReply / domain / domainType 都默认 null")
    void fiveArgConstructor_defaultsAreNull() {
        InvokeCommand cmd = new InvokeCommand("ak-1", "user-1", "session-1", "chat", "{}");

        assertEquals("ak-1", cmd.ak());
        assertEquals("user-1", cmd.userId());
        assertEquals("session-1", cmd.sessionId());
        assertEquals("chat", cmd.action());
        assertEquals("{}", cmd.payload());
        assertNull(cmd.suppressReply());
        assertNull(cmd.domain());
        assertNull(cmd.domainType());
    }

    @Test
    @DisplayName("6 参数构造器：domain / domainType 默认 null，suppressReply 由参数指定")
    void sixArgConstructor_domainDefaultsNull_suppressReplyExplicit() {
        InvokeCommand cmd = new InvokeCommand(
                "ak-1", "user-1", "session-1", "chat", "{}", Boolean.TRUE);

        assertEquals(Boolean.TRUE, cmd.suppressReply());
        assertNull(cmd.domain());
        assertNull(cmd.domainType());

        InvokeCommand cmd2 = new InvokeCommand(
                "ak-1", "user-1", "session-1", "chat", "{}", null);
        assertNull(cmd2.suppressReply());
    }

    @Test
    @DisplayName("8 参数构造器：所有字段都按入参赋值")
    void eightArgConstructor_allFieldsAssigned() {
        InvokeCommand cmd = new InvokeCommand(
                "ak-1", "user-1", "session-1", "chat", "{}",
                Boolean.FALSE, "helpdesk", "direct");

        assertEquals("ak-1", cmd.ak());
        assertEquals("user-1", cmd.userId());
        assertEquals("session-1", cmd.sessionId());
        assertEquals("chat", cmd.action());
        assertEquals("{}", cmd.payload());
        assertEquals(Boolean.FALSE, cmd.suppressReply());
        assertEquals("helpdesk", cmd.domain());
        assertEquals("direct", cmd.domainType());
    }

    @Test
    @DisplayName("record accessors domain() / domainType() 正常工作")
    void accessors_returnAssignedValues() {
        InvokeCommand cmd = new InvokeCommand(
                "ak-1", "user-1", "session-1", "chat", "{}",
                null, "im", "group");

        assertEquals("im", cmd.domain());
        assertEquals("group", cmd.domainType());
    }

    @Test
    @DisplayName("equals/hashCode：domain/domainType 参与 equals")
    void equalsHashCode_considersDomainFields() {
        InvokeCommand a = new InvokeCommand(
                "ak-1", "u-1", "s-1", "chat", "{}", null, "helpdesk", "direct");
        InvokeCommand b = new InvokeCommand(
                "ak-1", "u-1", "s-1", "chat", "{}", null, "helpdesk", "direct");
        InvokeCommand c = new InvokeCommand(
                "ak-1", "u-1", "s-1", "chat", "{}", null, "im", "direct"); // 不同 domain

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    @DisplayName("5 参数与 8 参数（domain=null/null/null）构造的 record 相等")
    void fiveArg_equivalentTo_eightArgWithNulls() {
        InvokeCommand five = new InvokeCommand("ak", "u", "s", "chat", "{}");
        InvokeCommand eight = new InvokeCommand("ak", "u", "s", "chat", "{}",
                null, null, null);
        assertEquals(five, eight);
    }
}
