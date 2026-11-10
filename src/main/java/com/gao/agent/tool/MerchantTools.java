package com.gao.agent.tool;

import com.gao.agent.util.MemoryId;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 业务工具：查商户、查券、查我的订单。
 *
 * <p><b>安全要点</b>：所有「我的」类工具都用 {@link ToolMemoryId} 拿当前登录用户，
 * <b>不接受模型传入的 userId</b>。因为模型可能被提示注入诱导去查别人的数据。
 *
 * <p>返回给模型的是「已经格式化好的文本」，不是 JSON —— 模型读自然语言更稳，也不容易漏字段。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MerchantTools {

    private final JdbcTemplate jdbcTemplate;

    /** 订单状态码字典，和 LocalLife 后端保持一致 */
    private static final Map<Integer, String> ORDER_STATUS = Map.of(
            1, "待支付", 2, "已支付", 3, "已核销", 4, "已取消");

    @Tool("按名称或地址模糊搜索商户，返回名称、地址、营业时间、人均、评分")
    public String searchShops(@P("商户名称或地址关键词，例如 烧烤、中央大街") String keyword) {
        log.debug("工具调用 searchShops keyword={}", keyword);
        String sql = """
                SELECT name, address, open_hours, avg_price, score
                FROM tb_shop
                WHERE name LIKE ? OR address LIKE ? OR area LIKE ?
                LIMIT 5
                """;
        String like = "%" + keyword + "%";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, like, like, like);
        if (rows.isEmpty()) {
            return "没有找到和「" + keyword + "」相关的商户。";
        }
        return rows.stream().map(r -> String.format(
                "%s｜地址：%s｜营业：%s｜人均：%s 分｜评分：%s",
                r.get("name"), r.get("address"), r.get("open_hours"),
                toYuan(r.get("avg_price")), r.get("score")))
                .collect(Collectors.joining("\n"));
    }

    @Tool("查询某家商户当前可用的优惠券，需要先通过 searchShops 拿到商户名称")
    public String listShopVouchers(@P("商户名称，例如 老王烧烤") String shopName) {
        log.debug("工具调用 listShopVouchers shopName={}", shopName);
        String sql = """
                SELECT v.title, v.sub_title, v.pay_value, v.actual_value, v.type
                FROM tb_voucher v
                JOIN tb_shop s ON s.id = v.shop_id
                WHERE s.name LIKE ?
                LIMIT 10
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, "%" + shopName + "%");
        if (rows.isEmpty()) {
            return "「" + shopName + "」当前没有可用优惠券。";
        }
        return rows.stream().map(r -> String.format(
                "%s｜%s｜现价 %s 元（原价 %s 元）｜类型：%s",
                r.get("title"), r.get("sub_title"),
                toYuan(r.get("pay_value")), toYuan(r.get("actual_value")),
                "1".equals(String.valueOf(r.get("type"))) ? "秒杀券" : "普通券"))
                .collect(Collectors.joining("\n"));
    }

    @Tool("查询当前用户自己持有的优惠券（只能查自己的，不需要也不能指定用户）")
    public String listMyVouchers(@ToolMemoryId String memoryId) {
        Long userId = requireUser(memoryId);
        log.debug("工具调用 listMyVouchers userId={}", userId);

        String sql = """
                SELECT v.title, v.sub_title, o.status, o.create_time
                FROM tb_voucher_order o
                JOIN tb_voucher v ON v.id = o.voucher_id
                WHERE o.user_id = ?
                ORDER BY o.create_time DESC
                LIMIT 10
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, userId);
        if (rows.isEmpty()) {
            return "当前账号还没有领取过优惠券。";
        }
        return rows.stream().map(r -> String.format(
                "%s｜%s｜状态：%s｜下单时间：%s",
                r.get("title"), r.get("sub_title"),
                ORDER_STATUS.getOrDefault(((Number) r.get("status")).intValue(), "未知"),
                r.get("create_time")))
                .collect(Collectors.joining("\n"));
    }

    @Tool("查询当前用户自己的订单列表（只能查自己的）")
    public String listMyOrders(@ToolMemoryId String memoryId) {
        Long userId = requireUser(memoryId);
        log.debug("工具调用 listMyOrders userId={}", userId);

        String sql = """
                SELECT o.id, v.title, o.status, o.pay_value, o.create_time
                FROM tb_voucher_order o
                JOIN tb_voucher v ON v.id = o.voucher_id
                WHERE o.user_id = ?
                ORDER BY o.create_time DESC
                LIMIT 10
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, userId);
        if (rows.isEmpty()) {
            return "当前账号还没有订单。";
        }
        return rows.stream().map(r -> String.format(
                "订单号 %s｜%s｜%s 元｜状态：%s｜%s",
                r.get("id"), r.get("title"), toYuan(r.get("pay_value")),
                ORDER_STATUS.getOrDefault(((Number) r.get("status")).intValue(), "未知"),
                r.get("create_time")))
                .collect(Collectors.joining("\n"));
    }

    /** 秒杀活动余量：动态数据，必须实时查库 */
    @Tool("查询某张秒杀券当前的剩余库存")
    public String seckillStock(@P("优惠券 id，例如 2") Long voucherId) {
        log.debug("工具调用 seckillStock voucherId={}", voucherId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?", voucherId);
        if (rows.isEmpty()) {
            return "券 " + voucherId + " 不是秒杀券或不存在。";
        }
        return "券 " + voucherId + " 当前剩余库存：" + rows.get(0).get("stock") + " 张。";
    }

    /** 秒杀活动时间窗：用户最常问「什么时候能抢」 */
    @Tool("查询某张秒杀券的活动开始与结束时间、当前库存")
    public String seckillActivity(@P("优惠券 id") Long voucherId) {
        log.debug("工具调用 seckillActivity voucherId={}", voucherId);
        String sql = """
                SELECT v.title, s.begin_time, s.end_time, s.stock
                FROM tb_seckill_voucher s
                JOIN tb_voucher v ON v.id = s.voucher_id
                WHERE s.voucher_id = ?
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, voucherId);
        if (rows.isEmpty()) {
            return "券 " + voucherId + " 不是秒杀券或不存在。";
        }
        Map<String, Object> r = rows.get(0);
        return String.format("%s｜活动时间：%s 至 %s｜当前库存：%s 张",
                r.get("title"), r.get("begin_time"), r.get("end_time"), r.get("stock"));
    }

    /** 探店笔记：知识库里只有商户基础信息，真实评价在 tb_blog */
    @Tool("查询某家商户的探店笔记（用户评价），需要商户名称")
    public String listShopBlogs(@P("商户名称，例如 老厨家") String shopName) {
        log.debug("工具调用 listShopBlogs shopName={}", shopName);
        String sql = """
                SELECT b.title, b.content, b.liked
                FROM tb_blog b
                JOIN tb_shop s ON s.id = b.shop_id
                WHERE s.name LIKE ?
                ORDER BY b.liked DESC
                LIMIT 3
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, "%" + shopName + "%");
        if (rows.isEmpty()) {
            return "「" + shopName + "」还没有探店笔记。";
        }
        return rows.stream().map(r -> String.format(
                "%s（%s 赞）\n  %s", r.get("title"), r.get("liked"), r.get("content")))
                .collect(Collectors.joining("\n"));
    }

    /** 券的使用规则：用户问「能不能外带」「节假日能不能用」时查这个 */
    @Tool("查询某张优惠券的使用规则和有效期，需要券 id")
    public String voucherRules(@P("优惠券 id") Long voucherId) {
        log.debug("工具调用 voucherRules voucherId={}", voucherId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT title, rules, actual_value, pay_value FROM tb_voucher WHERE id = ?", voucherId);
        if (rows.isEmpty()) {
            return "券 " + voucherId + " 不存在。";
        }
        Map<String, Object> r = rows.get(0);
        return String.format("%s｜现价 %s 元（原价 %s 元）｜使用规则：%s",
                r.get("title"), toYuan(r.get("pay_value")), toYuan(r.get("actual_value")), r.get("rules"));
    }

    /** 从 memoryId 解出用户；解不出来直接拒绝，绝不允许降级成「查全部」 */
    private Long requireUser(String memoryId) {
        Long userId = MemoryId.userIdOf(memoryId);
        if (userId == null) {
            throw new IllegalStateException("无法识别当前用户，拒绝查询");
        }
        return userId;
    }

    private String toYuan(Object fen) {
        if (fen == null) {
            return "-";
        }
        return String.valueOf(((Number) fen).longValue() / 100);
    }
}
