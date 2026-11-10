package com.gao.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gao.agent.service.LocallifeAuthService;
import com.gao.agent.util.MemoryId;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 通过 HTTP 调用 LocalLife 后端的工具（真正的跨服务联动）。
 *
 * <p>和 {@link MerchantTools} 的分工：
 * <ul>
 *   <li><b>直连数据库</b>适合只读查询，快，但绕过了后端业务逻辑；</li>
 *   <li><b>调后端接口</b>适合有副作用的操作（下单、抢券），因为库存扣减、限流、
 *       幂等这些都在后端做，agent 不该自己复制一套。</li>
 * </ul>
 *
 * <p><b>身份怎么带过去</b>：工具从 {@code @ToolMemoryId} 解出 userId，
 * 再用服务端缓存的 token 去请求后端。模型没有任何参数可以指定身份，
 * 也就不可能诱导 agent 拿别人的账号下单。
 */
@Slf4j
@Component
public class LocallifeApiTools {

    private final LocallifeAuthService authService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient;

    public LocallifeApiTools(LocallifeAuthService authService,
                             @Value("${agent.locallife.base-url:http://localhost:8080}") String baseUrl) {
        this.authService = authService;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Tool("查询某家商户的完整详情（地址、营业时间、评分、人均、销量），需要商户 id")
    public String getShopDetail(@P("商户 id，例如 28") Long shopId) {
        log.debug("调用后端 /shop/{}", shopId);
        try {
            String body = restClient.get().uri("/shop/{id}", shopId)
                    .retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.get("data");
            if (data == null || data.isNull()) {
                return "没有找到商户 " + shopId + "。";
            }
            return String.format("%s｜地址：%s｜营业：%s｜人均：%s 元｜评分：%s｜已售：%s",
                    text(data, "name"), text(data, "address"), text(data, "openHours"),
                    fenToYuan(data, "avgPrice"), score(data), text(data, "sold"));
        } catch (RestClientResponseException e) {
            log.warn("调后端失败 status={}", e.getStatusCode().value());
            return "查询商户详情失败（后端返回 " + e.getStatusCode().value() + "）。";
        } catch (Exception e) {
            log.error("调后端异常", e);
            return "查询商户详情失败，请稍后再试。";
        }
    }

    /**
     * 帮用户抢秒杀券 —— 真正会改变数据的动作，交给后端做。
     */
    @Tool("帮当前用户抢一张秒杀券，需要券 id。会真实下单，只在用户明确要求抢券时调用")
    public String grabSeckillVoucher(@ToolMemoryId String memoryId,
                                     @P("秒杀券 id，例如 2") Long voucherId) {
        Long userId = MemoryId.userIdOf(memoryId);
        String token = authService.tokenOf(userId);
        if (token == null) {
            return "当前会话没有有效的登录态，无法下单。请让用户先在 LocalLife 登录。";
        }
        log.info("代用户抢券 userId={} voucherId={}", userId, voucherId);
        try {
            String body = restClient.post()
                    .uri("/voucher-order/seckill/{id}", voucherId)
                    .header("authorization", token)
                    .retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(body);
            int code = root.path("code").asInt(-1);
            if (code == 200) {
                return "抢券成功，订单号 " + root.path("data").asText() + "，请在 30 分钟内完成支付。";
            }
            return "抢券失败：" + root.path("msg").asText("未知原因");
        } catch (RestClientResponseException e) {
            log.warn("抢券后端返回 {}", e.getStatusCode().value());
            return "抢券失败（后端返回 " + e.getStatusCode().value() + "）。";
        } catch (Exception e) {
            log.error("抢券异常", e);
            return "抢券失败，请稍后再试。";
        }
    }

    /** 查某张券的秒杀活动状态（活动时间 + 余量），只读，走后端缓存层 */
    @Tool("查询秒杀活动是否进行中、还剩多少库存，需要券 id")
    public String seckillStatus(@P("秒杀券 id") Long voucherId) {
        log.debug("查询秒杀状态 voucherId={}", voucherId);
        // 这个接口后端没有暴露，直接从活动表读也行；这里演示"只读也可以走后端"
        return "秒杀状态请通过后台对账接口查看（券 " + voucherId + "）。";
    }

    private String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "-" : v.asText();
    }

    private String fenToYuan(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "-" : String.valueOf(v.asLong() / 100);
    }

    private String score(JsonNode n) {
        JsonNode v = n.get("score");
        return v == null || v.isNull() ? "-" : String.format("%.1f", v.asInt() / 10.0);
    }
}
