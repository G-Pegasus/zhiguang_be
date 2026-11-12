# 项目文档
## 要求

- **JDK**: Java 21 (LTS)
- **Maven**: 3.9.x 或更高（推荐使用仓库中的 Maven Wrapper）

## 本地开发配置

`application.yml` 里的数据库口令、API Key、OSS 密钥全部是 `${VAR:}` 占位，**仓库里不含任何真实凭据**，所以直接 `mvn spring-boot:run` 会连不上 MySQL/Redis。先补一份本地配置：

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

填入自己的本地值后，用 `local` profile 启动：

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

`application-local.yml` 已在 `.gitignore` 中，不会被提交。

需要填的变量：

| 变量 | 说明 |
|---|---|
| `MYSQL_PASSWORD` | 本地 MySQL root 口令；库名默认 `zhiguang`，建表语句见 `db/schema.sql` |
| `REDIS_PASSWORD` | 本地 Redis 口令，没设密码就留空 |
| `DEEPSEEK_API_KEY` | AI 摘要用，留空则该功能报错，其余功能正常 |
| `OPENAI_API_KEY` | RAG 向量检索用（阿里云百炼 DashScope），同上 |
| `OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET` / `OSS_BUCKET` | 图片视频直传用，留空则发布带图知文会失败 |

JWT 签名密钥（`src/main/resources/keys/`）同样不在仓库里，本地自行生成一对：

```bash
mkdir -p src/main/resources/keys && openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out src/main/resources/keys/private.pem && openssl rsa -pubout -in src/main/resources/keys/private.pem -out src/main/resources/keys/public.pem
```

嫌麻烦也可以直接用 Docker Compose 跑整套依赖，配置从 `.env` 读取，见仓库根目录的 `DOCKER_DEPLOY.md`。

<div style="display: flex; gap: 10px;">
  <img src="http://zhiguangapp.oss-cn-beijing.aliyuncs.com/posts/262804640385601536/images/20251226/11a8438f.png" width="250" />
  <img src="http://zhiguangapp.oss-cn-beijing.aliyuncs.com/posts/262804640385601536/images/20251226/4035ca79.png" width="250" />
  <img src="http://zhiguangapp.oss-cn-beijing.aliyuncs.com/posts/262804640385601536/images/20251226/40b80f25.png" width="250" />
</div>

![文档1](http://zhiguangapp.oss-cn-beijing.aliyuncs.com/posts/262804640385601536/images/20251226/43eb8fe1.png)
![文档2](http://zhiguangapp.oss-cn-beijing.aliyuncs.com/posts/262804640385601536/images/20251226/42b24575.png)

# 知光平台-知识获取与分享社区
后端 & 前端开发（前端采用 AI 辅助开发）
- **后端地址**：https://github.com/G-Pegasus/zhiguang_be
- **前端地址**：https://github.com/G-Pegasus/zhiguang_fe
- **项目概述**：知识社区 APP（后续考虑支持付费），支持发布知识、点赞/收藏、关注取关、首页 Feed 展示与对象存储直传，AI 生成摘要等等。项目各模块进行了充分详细的设计以满足高并发和高可用需求
- **技术栈**：后端 Java 21 + Spring Boot + Spring Security + Spring AI + RAG + MyBatis + MySQL + Redis + Kafka + Caffeine + 阿里云 OSS + Canal + Elasticsearch ；前端 React + Vite
- **项目细节与亮点**：
    - **认证系统**：开发基于 Spring Security 的 JWT 双令牌认证系统，采用 RS256 签名 + Redis 刷新令牌白名单，实现 15 分钟访问令牌 + 7 天刷新令牌的安全会话管理，支持即时令牌撤销，兼顾高安全与高性能。
    - **计数系统**：笔记维度(点赞收藏)与用户维度(关注取关) 以 Redis 作为底层存储系统，采用定制化 Redis SDS 二进制紧凑计数，使用 Lua 脚本进行原子更新，并实现了采样一致性校验与自愈重建。定制化 Redis SDS
    - **发布系统**：采用渐进式发布流程，发布的图片、视频，Markdown 文档等都存入 OSS 对象存储系统，采用后端发布预签名+前端直传的形式上传，节省前后端传输资源渐进式发布流程。并接入 DeepSeek AI 一键生成文章摘要。
    - **用户关系系统**：实现关注功能，采用一主多从+事件驱动模型。粉丝表，计数系统，列表缓存都作为关注表的伪从。关注事件发生时，在同一事务中插入关注表和 Outbox 表，使用 Canal 订阅 Outbox 表的 binlog，并将变更事件发布到 Kafka 异步更新其他数据源。Outbox 模式
    - **点赞系统**：采用异步写+写聚合Kafka 异步写+写聚合的形式应对高并发写场景。采用位图的结构高效实现幂等和判重。读取遇到异常或缺失时，基于位图做按需重建，保证最终一致。并用 Kafka 做“灾难回放”的兜底操作。分片位图+计数重建策略
    - **Feed 流**：采用三级缓存架构且设计了缓存一致性策略，本地 Caffeine + Redis 页面缓存 + Redis 片段缓存。自定义 hotkey 探测机制自定义 hotkey 探测，基于热点检测按层级延长缓存时长，叠加随机抖动抗雪崩。并设置单飞锁(single-flight)避免同一页并发回源风暴。Feed 三级缓存设计
    - **搜索系统**：基于 Elasticsearch 构建内容搜索与联想建议功能，支持关键词检索，标签过滤，采用 search_after 游标分页保证深分页稳定性。同时通过 function_score 融合 BM25 相关性与点赞等业务权重优化排序，保证结果的相关性；并使用 ES 的 completion suggester 实现低延迟前缀联想。
    - **AI 问答系统**：开发知光平台 RAG 知识问答系统，实现用户调用接口→索引检查→向量检索→Prompt 构造→大模型流式生成的全流程，通过合理分块、幂等删除保持单一版本、预索引减少首次提问等待时间等，显著提升用户围绕单篇知文的智能问答效率与准确性。

# 项目前端页面展示
<div style="display: flex; gap: 10px;">
  <img src="https://free.picui.cn/free/2026/03/29/69c8db2c32d74.png" width="800" />
  <img src="https://free.picui.cn/free/2026/03/29/69c8db2ead009.png" width="800" />
  <img src="https://free.picui.cn/free/2026/03/29/69c8db2b93e32.png" width="800" />
  <img src="https://free.picui.cn/free/2026/03/29/69c8db2b89818.png" width="800" />
  <img src="https://free.picui.cn/free/2026/03/29/69c8db2bd181e.png" width="800" />
  <img src="https://free.picui.cn/free/2026/03/29/69c8db2c30a4b.png" width="800" />
</div>
