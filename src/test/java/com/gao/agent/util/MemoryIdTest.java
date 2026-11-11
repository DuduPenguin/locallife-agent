package com.gao.agent.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * memoryId 编解码。
 *
 * <p>看着只是字符串拼接，但它是**工具层身份校验的唯一入口**：
 * 工具用 {@code userIdOf(memoryId)} 取用户 id，这里解析错了，就会查错人的订单。
 */
class MemoryIdTest {

    @Test
    @DisplayName("组装：userId + sessionId")
    void shouldBuildMemoryId() {
        assertEquals("u1:s3f2a", MemoryId.of(1L, "s3f2a"));
    }

    @Test
    @DisplayName("sessionId 为空时用 default 兜底")
    void shouldFallbackToDefaultSession() {
        assertEquals("u1:default", MemoryId.of(1L, null));
        assertEquals("u1:default", MemoryId.of(1L, "  "));
    }

    @Test
    @DisplayName("解析：正常情况")
    void shouldParseUserId() {
        assertEquals(1L, MemoryId.userIdOf("u1:s3f2a"));
        assertEquals(42L, MemoryId.userIdOf("u42"));
        assertEquals(10086L, MemoryId.userIdOf("u10086:web-abcd"));
    }

    @Test
    @DisplayName("解析失败返回 null，而不是抛异常或返回 0")
    void shouldReturnNullOnBadInput() {
        assertNull(MemoryId.userIdOf(null));
        assertNull(MemoryId.userIdOf(""));
        assertNull(MemoryId.userIdOf("s3f2a"));        // 没有 u 前缀
        assertNull(MemoryId.userIdOf("uabc:s1"));      // id 不是数字
    }

    @Test
    @DisplayName("往返一致：of 之后能原样解出 userId")
    void shouldRoundTrip() {
        for (long userId : new long[]{1L, 2L, 999L, Long.MAX_VALUE}) {
            String memoryId = MemoryId.of(userId, "session");
            assertEquals(userId, MemoryId.userIdOf(memoryId));
        }
    }
}
