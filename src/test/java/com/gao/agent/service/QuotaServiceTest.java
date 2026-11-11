package com.gao.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每日配额。
 *
 * <p>这段逻辑错了有两种后果：配额形同虚设（被脚本刷爆 token），
 * 或者算错次数（正常用户被误拦）。两种情况都值得一条单测。
 */
@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private QuotaService quotaService;

    @BeforeEach
    void setUp() {
        quotaService = new QuotaService(stringRedisTemplate);
        ReflectionTestUtils.setField(quotaService, "dailyLimit", 2L);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("配额 2：前两次放行，第三次拒绝")
    void shouldAllowUntilLimitThenReject() {
        AtomicLong counter = new AtomicLong();
        when(valueOperations.increment(anyString())).thenAnswer(inv -> counter.incrementAndGet());

        assertTrue(quotaService.tryAcquire(1L), "第 1 次应该放行");
        assertTrue(quotaService.tryAcquire(1L), "第 2 次应该放行");
        assertFalse(quotaService.tryAcquire(1L), "第 3 次应该被拒绝");
    }

    @Test
    @DisplayName("第一次计数时设置过期时间，避免 key 永久占内存")
    void shouldSetExpireOnFirstIncrement() {
        when(valueOperations.increment(anyString())).thenReturn(1L);
        quotaService.tryAcquire(1L);
        verify(stringRedisTemplate, times(1)).expire(anyString(), eq(Duration.ofDays(2)));
    }

    @Test
    @DisplayName("后续计数不再重复设置过期时间")
    void shouldNotResetExpireOnLaterCalls() {
        when(valueOperations.increment(anyString())).thenReturn(2L);
        quotaService.tryAcquire(1L);
        verify(stringRedisTemplate, never()).expire(anyString(), eq(Duration.ofDays(2)));
    }

    @Test
    @DisplayName("剩余次数 = 上限 - 已用；未用过时等于上限")
    void shouldReportRemain() {
        when(valueOperations.get(anyString())).thenReturn(null);
        assertEquals(2L, quotaService.remain(1L), "没用过时应该剩满额");

        when(valueOperations.get(anyString())).thenReturn("2");
        assertEquals(0L, quotaService.remain(1L), "用满时应该剩 0");

        when(valueOperations.get(anyString())).thenReturn("5");
        assertEquals(0L, quotaService.remain(1L), "超出时不应该出现负数");
    }

    @Test
    @DisplayName("dailyLimit <= 0 表示不限流，直接放行")
    void shouldAllowAllWhenLimitDisabled() {
        ReflectionTestUtils.setField(quotaService, "dailyLimit", 0L);
        assertTrue(quotaService.tryAcquire(1L));
        assertEquals(0L, quotaService.remain(1L));
    }
}
