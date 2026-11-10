# LocalLife 商户助手（RAG + 工具调用）

给 LocalLife 本地生活平台加的自然语言入口：用户问「老厨家几点开门」「我有哪些券」，
助手先检索商户知识库，需要实时数据时调工具查数据库或调后端接口，最后生成回答。

后端项目：[locallife](https://github.com/DuduPenguin/locallife)

## 它和普通 RAG demo 的区别

| 维度 | 常见做法 | 本项目 |
|---|---|---|
| 身份 | 请求头传 userId | **复用后端登录态**：读 LocalLife 的 Redis token 换 userId |
| 数据 | 独立的知识库文件 | 同时用后端的 **5 张业务表** + 自建 `kb_chunk` 全文索引 |
| 召回 | 纯向量 | **向量 + 关键词双路，RRF 融合** |
| 排序 | 召回即结果 | 召回 15 条 → **embedding 精排** → 5 条 |
| 多轮 | 直接把原句丢给检索 | 抽出代词做**指代改写**再检索 |
| 工具 | 只能读 | 能**代用户抢券**（调后端接口，身份由服务端注入） |
| 防护 | 无 | 每日配额 + 知识库指纹幂等 + 工具身份隔离 |

## 架构

```
                       authorization (LocalLife 的 token)
                                    │
                        ┌───────────▼────────────┐
                        │  ChatController        │
                        │  身份解析 → 配额 → 改写 │
                        └───────────┬────────────┘
                                    ▼
                        ┌────────────────────────┐
                        │  MerchantAssistant     │  @AiService
                        │  system.txt + 会话记忆  │
                        └──┬──────────┬──────────┘
                           │          │
          ┌────────────────▼──┐   ┌───▼─────────────────┐
          │ HybridContentRetriever │  │ 工具层（10 个）      │
          │  向量(Redis) + 关键词   │  │ 直连 DB / 调后端 API │
          │  → RRF → 精排          │  │ 身份从 @ToolMemoryId │
          └───────────────────────┘  └─────────────────────┘
```

## 关键实现

**① 复用后端登录态**（`LocallifeAuthService`）

后端把登录态写在 Redis **db 1** 的 `login:token:{token}` Hash 里，agent 单独建一个连接读它，
顺带做滑动续期（和 `RefreshTokenInterceptor` 行为一致）。

**② 双路召回 + RRF**（`HybridContentRetriever`）

向量召回解决语义相近，关键词召回解决专有名词（"老厨肉""张包铺"）命中不稳。
两路分数尺度不同，不能直接加权，所以用 RRF 只看排名：

```
score(d) = Σ 1 / (60 + rank_i(d))
```

**③ 精排**（`EmbeddingReranker`）

召回 15 条候选，用 embedding 重算 query 与候选的余弦相似度，取前 5。
召回负责"不漏"，精排负责"排序准"。

**④ 指代改写**（`QueryRewriteService`）

只在句子里出现代词（它/那家/这个）或句子很短时才触发，避免每轮多一次模型调用。

**⑤ 工具身份隔离**（`MerchantTools` / `LocallifeApiTools`）

```java
@Tool("查询当前用户自己的订单")
public String listMyOrders(@ToolMemoryId String memoryId) {
    Long userId = MemoryId.userIdOf(memoryId);   // 从会话取，模型无法指定
}
```

模型没有 userId 参数可传，所以不可能被诱导去查别人的订单或抢别人的券。

## 运行

### 1. 前置

| 组件 | 说明 |
|---|---|
| LocalLife 后端 | 默认 `http://localhost:8080`，提供登录态与业务接口 |
| Redis | `localhost:6379`；agent 用 db 0，后端登录态在 db 1 |
| MySQL | 同一个 `locallife` 库 |
| 模型 | 任意 OpenAI 兼容接口（默认通义千问） |

### 2. 建表

```bash
mysql -h127.0.0.1 -uroot -p123456 locallife --default-character-set=utf8mb4 < sql/agent_schema.sql
```

### 3. 配置 Key 并启动

```powershell
$env:API-KEY="sk-你的key"
mvn spring-boot:run
```

启动日志：

```
向量检索器初始化：maxResults=5, minScore=0.6
知识库灌入完成：10 个文档 → 86 个分块（MySQL 86 行 + Redis 86 向量）
```

### 4. 验证

```powershell
# 用后端的 token 换身份
curl "http://localhost:8081/agent/whoami" -H "authorization: 你的token"

# 只看召回结果（不调模型）
curl "http://localhost:8081/agent/search?q=老厨家几点开门"

# 完整问答（SSE 流式）
curl -N "http://localhost:8081/agent/chat?message=老厨家几点开门" -H "authorization: 你的token"
```

## 检索评测

40 条评测集（描述型问题不带店名、专有名词题、券规则题），三组对照：

```powershell
# A 纯向量
mvn spring-boot:run "-Dspring-boot.run.arguments=--agent.eval.run=true --server.port=0 --agent.retrieval.mode=vector --agent.rerank.enabled=false"
# B 双路 + RRF
mvn spring-boot:run "-Dspring-boot.run.arguments=--agent.eval.run=true --server.port=0 --agent.retrieval.mode=hybrid --agent.rerank.enabled=false"
# C 双路 + 精排
mvn spring-boot:run "-Dspring-boot.run.arguments=--agent.eval.run=true --server.port=0 --agent.retrieval.mode=hybrid --agent.rerank.enabled=true"
```

实测结果（26 个知识块，40 条问题）：

| 组 | 召回策略 | Hit@1 | Hit@3 | Hit@5 | MRR | 平均延迟 |
|---|---|---|---|---|---|---|
| A | 纯向量 | 85.0% | 97.5% | 97.5% | 0.908 | 169 ms |
| **B** | **双路 + RRF** | 85.0% | **100%** | **100%** | **0.921** | **169 ms** |
| C | 双路 + 精排 | 80.0% | 97.5% | 97.5% | 0.883 | 221 ms |

**结论：双路召回有效，我实现的精排反而让指标下降。**

- 双路把 Hit@3 从 97.5% 拉到 100%、MRR 从 0.908 提到 0.921 —— 关键词那一路确实补上了
  专有名词（"锅包肉""张包铺"）召回不稳的问题。
- 精排用 embedding 重算相似度，等于**把 RRF 里关键词的信号丢掉了**，
  再加上每次多一次 embedding 调用（延迟 169 → 221ms），所以是负收益。

后续改进方向（没做）：精排时保留 RRF 分数做加权，或者换成真正的 cross-encoder / LLM 精排，
而不是用同源的 embedding 再算一遍。

> 这个负结果本身是实验的一部分：不跑对照组，就会误以为"加了精排一定更好"。

## 实测验证过的行为

| 场景 | 结果 |
|---|---|
| 用后端 token 换身份 | `GET /agent/whoami` → `userId=1，nickName=ceshi，今日剩余 100 次` |
| 知识库灌入 | 10 个文档 → 26 个分块，MySQL 26 行 + Redis 26 个向量 |
| RAG 问答 | 「老厨家几点开门」→ 11:00-23:00 / 西大直街 40 号 / 人均 143 / 锅包肉 |
| 工具调用 | 「我有哪些券」→ 查真实订单表，返回 1 张已取消的券 |
| 工具对比 | 「张包铺的排骨包子怎么样」→ 综合商户信息 + 探店笔记，并对比了徐包铺 |
| 越权拦截 | 「查 user_id=5 的订单」→ 拒绝，只允许查自己 |
| 指代改写 | 第 1 轮问川渝火锅，第 2 轮「它几点关门」→ 改写为「川渝火锅（哈工大店）几点关门」 |
| 限流 | 配额设 2，前 2 次正常、第 3 次返回 429 |
| Redis 隔离 | db0 只有 agent 自己的 key，db1 只有后端的 `login:token:*` |

> ⚠️ **Redis 隔离的坑**：Spring Boot 的 Redis 自动配置是
> `@ConditionalOnMissingBean(RedisConnectionFactory)`。只要容器里出现任意一个工厂，
> 它就不生效；如果有两个工厂而没有显式指定，自动配置可能挑到"后端那一个"，
> 结果 agent 的配额、会话记忆全写进后端的库。
> 本项目显式声明两个工厂 + 两个模板（`@Primary` 给 agent 自己那套）来避免这个问题。

## 目录

```
src/main/java/com/gao/agent
├── aiservice/     MerchantAssistant（@AiService 装配）
├── config/        AgentConfig（记忆 + 向量检索）、LocallifeRedisConfig（读后端 db1）
├── controller/    ChatController（SSE）、SearchController（检索调试）
├── ingest/        KnowledgeIngestRunner（切分 → MySQL + Redis，指纹幂等）
├── memory/        RedisChatMemoryStore
├── retrieval/     HybridContentRetriever、KeywordContentRetriever、EmbeddingReranker
├── service/       LocallifeAuthService、QueryRewriteService、QuotaService
├── tool/          MerchantTools（8 个）、LocallifeApiTools（2 个）
├── util/          MemoryId
└── eval/          EvalRunner（Hit@k / MRR 跑批）
```

## 已知限制

| 项 | 说明 |
|---|---|
| 精排用 embedding 而非 cross-encoder | 本机没有 cross-encoder 模型，embedding 重算相关度是折中 |
| 知识库只有商户资料 | 10 个 md 文件，规模偏小，评测样本 50 条 |
| 多轮改写依赖上一次问答 | 会话里没有上下文时不做改写 |
