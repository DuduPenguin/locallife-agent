package com.gao.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;

/**
 * 每日配额：防刷。
 *
 * <p>大模型调用是**按 token 计费**的，一个脚本循环调用几十次就能把额度刷光。
 * 这里用 Redis 计数器做每用户每日限制，key 带日期，第二天自动重置。
 *
 * <p>为什么不用滑动窗口限流：问答场景关心的是"一天能用多少次"，
 * 不是"每秒多少次"，固定窗口足够，也更省 Redis 操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private static final String KEY_PREFIX = "agent:quota:";

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${agent.quota.daily-limit:100}")
    private long dailyLimit;

    /**
     * 尝试消耗一次配额。
     *
     * @return true 允许本次调用；false 超额
     */
    public boolean tryAcquire(Long userId) {
        if (userId == null || dailyLimit <= 0) {
            return true;
        }
        String key = KEY_PREFIX + userId + ":" + LocalDate.now();
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // 首次计数时设过期，保留两天便于排查
            stringRedisTemplate.expire(key, Duration.ofDays(2));
        }
        if (count != null && count > dailyLimit) {
            log.warn("用户 {} 超出每日配额 {}/{}", userId, count, dailyLimit);
            return false;
        }
        return true;
    }

    /** 查剩余次数（用于提示语） */
    public long remain(Long userId) {
        if (userId == null) {
            return dailyLimit;
        }
        String value = stringRedisTemplate.opsForValue()
                .get(KEY_PREFIX + userId + ":" + LocalDate.now());
        long used = value == null ? 0 : Long.parseLong(value);
        return Math.max(0, dailyLimit - used);
    }
}
