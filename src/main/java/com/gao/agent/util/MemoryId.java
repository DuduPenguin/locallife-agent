package com.gao.agent.util;

/**
 * memoryId 编解码。
 *
 * <p>格式：{@code u{userId}:{sessionId}}，例如 {@code u1:s3f2a}。
 *
 * <p>为什么把用户身份编进 memoryId：LangChain4j 的 {@code @ToolMemoryId}
 * 能把当前调用的 memoryId 直接注入到 Tool 方法里，于是工具能拿到「谁在问」，
 * 而不需要相信模型自己填的 userId 参数。
 */
public final class MemoryId {

    private MemoryId() {
    }

    /** 组装：userId + 会话 id（会话 id 可为空，视为单会话） */
    public static String of(Long userId, String sessionId) {
        String session = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        return "u" + userId + ":" + session;
    }

    /** 从 memoryId 解出 userId，解析失败返回 null */
    public static Long userIdOf(String memoryId) {
        if (memoryId == null || !memoryId.startsWith("u")) {
            return null;
        }
        int colon = memoryId.indexOf(':');
        String num = colon < 0 ? memoryId.substring(1) : memoryId.substring(1, colon);
        try {
            return Long.parseLong(num);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
