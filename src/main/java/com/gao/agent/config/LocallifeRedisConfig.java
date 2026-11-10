package com.gao.agent.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 两套 Redis 连接：agent 自己一套（db 0），LocalLife 后端一套（db 1）。
 *
 * <p><b>为什么必须显式声明两个</b>：Spring Boot 的 Redis 自动配置是
 * {@code @ConditionalOnMissingBean(RedisConnectionFactory)}——只要容器里出现任意一个
 * RedisConnectionFactory，它就不再生效。如果只声明"后端连接"那一个，
 * 自动配置的 {@code stringRedisTemplate} 就会绑到它身上，
 * 结果 agent 自己的配额、会话记忆全写进了后端的库。
 *
 * <p>所以这里把两套都显式声明，并给 agent 自己那套加 {@code @Primary}，
 * 让自动配置的 {@code stringRedisTemplate} / 其他组件都默认用它。
 */
@Configuration
public class LocallifeRedisConfig {

    /** agent 自己的库：向量库、会话记忆、配额、token 缓存 */
    @Primary
    @Bean(name = "redisConnectionFactory")
    public LettuceConnectionFactory agentRedisConnectionFactory(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.database:0}") int database,
            @Value("${spring.data.redis.password:}") String password) {
        return build(host, port, database, password);
    }

    /** 只读后端库：登录态 login:token:* */
    @Bean(name = "locallifeRedisConnectionFactory")
    public LettuceConnectionFactory locallifeRedisConnectionFactory(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${agent.locallife.redis-database:1}") int database,
            @Value("${spring.data.redis.password:}") String password) {
        return build(host, port, database, password);
    }

    @Bean(name = "locallifeRedisTemplate")
    public StringRedisTemplate locallifeRedisTemplate(
            @Qualifier("locallifeRedisConnectionFactory") LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * agent 自己的 StringRedisTemplate（db 0）。
     *
     * <p>不依赖自动配置：容器里有两个 RedisConnectionFactory 时，
     * 自动配置挑哪个并不可靠（实测会挑到后端那个，导致配额写进后端的库）。
     * 这里显式绑定，注入时按类型就能拿到 db 0 的模板。
     */
    @Primary
    @Bean(name = "stringRedisTemplate")
    public StringRedisTemplate agentStringRedisTemplate(
            @Qualifier("redisConnectionFactory") LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    private LettuceConnectionFactory build(String host, int port, int database, String password) {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(host, port);
        cfg.setDatabase(database);
        if (password != null && !password.isBlank()) {
            cfg.setPassword(password);
        }
        return new LettuceConnectionFactory(cfg);
    }
}
