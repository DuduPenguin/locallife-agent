package com.gao.agent.aiservice;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import reactor.core.publisher.Flux;

/**
 * 商户助手：一个「检索 + 工具 + 记忆」三件套的 AI Service。
 *
 * <p>显式装配（EXPLICIT）的好处是每个零件都能看到名字，
 * 出问题能一眼定位是模型、检索还是工具的锅。
 *
 * <p>注意 {@code chat} 方法只有两个参数：
 * <ul>
 *   <li>{@code @MemoryId} —— 会话标识，同时被 @ToolMemoryId 用来给工具传身份；</li>
 *   <li>{@code @UserMessage} —— 用户这句话。</li>
 * </ul>
 * 用户身份<b>不走用户消息</b>，避免模型把「我是 user_id=5」当成真的。
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        streamingChatModel = "openAiStreamingChatModel",
        chatMemoryProvider = "chatMemoryProvider",
        contentRetriever = "contentRetriever",
        tools = {"merchantTools", "locallifeApiTools"}
)
public interface MerchantAssistant {

    @SystemMessage(fromResource = "system.txt")
    Flux<String> chat(@MemoryId String memoryId, @UserMessage String message);
}
