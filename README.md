# 项目详细文档购买：
**小红书搜：程序员流年**

**项目说明：开源代码中缺少配置文件，购买后获得全部代码**

商品详情：https://www.xiaohongshu.com/goods-detail/691f20586ea74e00019b3a3d

# 文档示例
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
