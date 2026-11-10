package com.gao.agent.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 只读连接 LocalLife 后端的 Redis（db 1）。
 *
 * <p>为什么要单独建一个连接：agent 自己的向量库、会话记忆在 db 0，
 * 而后端的登录态在 db 1。用 {@code SELECT} 切库会影响全局连接，所以用两个独立的
 * ConnectionFactory —— 一个给 agent 自己（Boot 自动配置的那个），一个只读后端。
 *
 * <p>这样 agent 不用再信任请求头里的 {@code X-User-Id}，而是拿 authorization
 * 去后端那把 token 换成 userId，和真实登录态完全一致。
 */
@Configuration
public class LocallifeRedisConfig {

    @Bean(name = "locallifeRedisConnectionFactory")
    public LettuceConnectionFactory locallifeRedisConnectionFactory(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${agent.locallife.redis-database:1}") int database,
            @Value("${spring.data.redis.password:}") String password) {

        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(host, port);
        cfg.setDatabase(database);
        if (password != null && !password.isBlank()) {
            cfg.setPassword(password);
        }
        return new LettuceConnectionFactory(cfg);
    }

    @Bean(name = "locallifeRedisTemplate")
    public StringRedisTemplate locallifeRedisTemplate(
            @Qualifier("locallifeRedisConnectionFactory") LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
