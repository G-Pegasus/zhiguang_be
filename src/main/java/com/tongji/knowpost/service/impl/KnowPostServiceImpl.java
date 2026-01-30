package com.tongji.knowpost.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tongji.counter.service.UserCounterService;
import com.tongji.knowpost.service.KnowPostService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.knowpost.id.SnowflakeIdGenerator;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.knowpost.model.KnowPostDetailRow;
import com.tongji.knowpost.api.dto.KnowPostDetailResponse;
import com.github.benmanes.caffeine.cache.Cache;
import com.tongji.counter.service.CounterService;
import com.tongji.storage.config.OssProperties;
import com.tongji.llm.rag.RagIndexService;
import com.tongji.cache.hotkey.HotKeyDetector;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class KnowPostServiceImpl implements KnowPostService {

    private final KnowPostMapper mapper;
    @Resource
    private final SnowflakeIdGenerator idGen;
    private final ObjectMapper objectMapper;
    private final OssProperties ossProperties;
    private final CounterService counterService;
    private final UserCounterService userCounterService;
    private final StringRedisTemplate redis;
    @Qualifier("knowPostDetailCache")
    private final Cache<String, KnowPostDetailResponse> knowPostDetailCache;
    private final HotKeyDetector hotKey;
    private static final Logger log = LoggerFactory.getLogger(KnowPostServiceImpl.class);
    private static final int DETAIL_LAYOUT_VER = 1;
    private final ConcurrentHashMap<String, Object> singleFlight = new ConcurrentHashMap<>();
    private final RagIndexService ragIndexService;

    // 手动编写构造器，Spring的@Qualifier直接标注在参数上（核心）
    public KnowPostServiceImpl(
            KnowPostMapper mapper,
            SnowflakeIdGenerator idGen,
            ObjectMapper objectMapper,
            OssProperties ossProperties,
            CounterService counterService,
            UserCounterService userCounterService,
            StringRedisTemplate redis,
            @Qualifier("knowPostDetailCache") Cache<String, KnowPostDetailResponse> knowPostDetailCache,
            HotKeyDetector hotKey,
            RagIndexService ragIndexService
    ) {
        this.mapper = mapper;
        this.idGen = idGen;
        this.objectMapper = objectMapper;
        this.ossProperties = ossProperties;
        this.counterService = counterService;
        this.userCounterService = userCounterService;
        this.redis = redis;
        this.knowPostDetailCache = knowPostDetailCache; // 带@Qualifier的参数赋值
        this.hotKey = hotKey;
        this.ragIndexService = ragIndexService;
    }
    /**
     * 创建草稿并返回新 ID。
     */
    @Transactional
    public long createDraft(long creatorId) {
        long id = idGen.nextId();
        Instant now = Instant.now();
        KnowPost post = KnowPost.builder()
                .id(id)
                .creatorId(creatorId)
                .status("draft")
                .type("image_text")
                .visible("public")
                .isTop(false)
                .createTime(now)
                .updateTime(now)
                .build();
        mapper.insertDraft(post);
        return id;
    }

    /**
     * 确认内容上传（写入 objectKey、etag、大小、校验和，并生成公共 URL）。
     */
    @Transactional
    public void confirmContent(long creatorId, long id, String objectKey, String etag, Long size, String sha256) {
        // 缓存双删
        invalidateCache(id);

        KnowPost post = KnowPost.builder()
                .id(id)
                .creatorId(creatorId)
                .contentObjectKey(objectKey)
                .contentEtag(etag)
                .contentSize(size)
                .contentSha256(sha256)
                .contentUrl(publicUrl(objectKey))
                .updateTime(Instant.now())
                .build();

        int updated = mapper.updateContent(post);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        invalidateCache(id);

        // 触发一次预索引（草稿阶段可能因可见性/状态被跳过）
        try {
            ragIndexService.ensureIndexed(id);
        } catch (Exception e) {
            log.warn("Pre-index after content confirm failed, post {}: {}", id, e.getMessage());
        }
    }

    /**
     * 更新元数据：标题、标签、可见性、置顶、图片列表等。
     */
    @Transactional
    public void updateMetadata(long creatorId, long id, String title, Long tagId, List<String> tags, List<String> imgUrls, String visible, Boolean isTop, String description) {
        invalidateCache(id);

        KnowPost post = KnowPost.builder()
                .id(id)
                .creatorId(creatorId)
                .title(title)
                .tagId(tagId)
                .tags(toJsonOrNull(tags))
                .imgUrls(toJsonOrNull(imgUrls))
                .visible(visible)
                .isTop(isTop)
                .description(description)
                .type("image_text")
                .updateTime(Instant.now())
                .build();

        int updated = mapper.updateMetadata(post);

        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        invalidateCache(id);
    }

    /**
     * 发布草稿，设置状态与发布时间。
     */
    @Transactional
    public void publish(long creatorId, long id) {
        int updated = mapper.publish(id, creatorId);

        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }
        try {
            userCounterService.incrementPosts(creatorId, 1);
        } catch (Exception ignored) {}

        // 发布成功后触发一次预索引，减少首次问答冷启动
        try {
            ragIndexService.ensureIndexed(id);
        } catch (Exception e) {
            log.warn("Pre-index after publish failed, post {}: {}", id, e.getMessage());
        }
    }

    /**
     * 设置置顶。
     */
    @Transactional
    public void updateTop(long creatorId, long id, boolean isTop) {
        invalidateCache(id);

        int updated = mapper.updateTop(id, creatorId, isTop);

        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        invalidateCache(id);
    }

    /**
     * 设置可见性（权限）。
     */
    @Transactional
    public void updateVisibility(long creatorId, long id, String visible) {
        if (!isValidVisible(visible)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "可见性取值非法");
        }

        invalidateCache(id);

        int updated = mapper.updateVisibility(id, creatorId, visible);

        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        invalidateCache(id);
    }

    /**
     * 软删除。
     */
    @Transactional
    public void delete(long creatorId, long id) {
        invalidateCache(id);

        int updated = mapper.softDelete(id, creatorId);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }

        invalidateCache(id);
    }

    private boolean isValidVisible(String visible) {
        if (visible == null) {
            return false;
        }

        return switch (visible) {
            case "public", "followers", "school", "private", "unlisted" -> true;
            default -> false;
        };
    }

    private String toJsonOrNull(List<String> list) {
        if (list == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "JSON 处理失败");
        }
    }

    private String publicUrl(String objectKey) {
        String publicDomain = ossProperties.getPublicDomain();

        if (publicDomain != null && !publicDomain.isBlank()) {
            return publicDomain.replaceAll("/$", "") + "/" + objectKey;
        }

        return "https://" + ossProperties.getBucket() + "." + ossProperties.getEndpoint() + "/" + objectKey;
    }

    /**
     * 获取知文详情（含作者信息、图片列表）。
     * <p>
     * 流程：
     * 1. 尝试读取 Redis 缓存。
     * 2. 若缓存命中，直接返回（需叠加实时计数与用户状态）。
     * 3. 若缓存未命中，使用 SingleFlight 锁机制防止缓存击穿。
     * 4. 锁内再次检查缓存（双重检查）。
     * 5. 若仍未命中，回源查询数据库。
     * 6. 校验内容状态与访问权限。
     * 7. 组装数据并写入 Redis 缓存（带随机过期时间与热点自动延期）。
     * 8. 返回最终结果（叠加用户维度状态）。
     * </p>
     *
     * @param id 知文 ID
     * @param currentUserIdNullable 当前用户 ID（可空，用于判断权限与点赞状态）
     * @return 知文详情响应
     */
    @Transactional(readOnly = true)
    public KnowPostDetailResponse getDetail(long id, Long currentUserIdNullable) {
        // 1. 构造缓存 Key：knowpost:detail:{id}:v{version}
        String pageKey = "knowpost:detail:" + id + ":v" + DETAIL_LAYOUT_VER;
        
        // 0. L1 本地缓存（Caffeine）
        KnowPostDetailResponse local = knowPostDetailCache.getIfPresent(pageKey);
        if (local != null) {
            recordHotKeyAndExtendTtl(id, pageKey);
            log.info("detail source=local key={}", pageKey);
            return enrichDetailResponse(local, currentUserIdNullable, true);
        }

        String cached = redis.opsForValue().get(pageKey);

        // 2. 第一次尝试处理缓存命中
        // 如果缓存中有数据（且不是 "NULL"），则解析并返回
        KnowPostDetailResponse resp = tryProcessCacheHit(cached, id, pageKey, currentUserIdNullable, "page");
        if (resp != null) {
            return resp;
        }

        // 3. 缓存未命中，进入 SingleFlight 模式
        // 对同一个 pageKey 加锁，防止高并发下大量请求同时打到数据库（缓存击穿/惊群效应）
        Object lock = singleFlight.computeIfAbsent(pageKey, k -> new Object());
        synchronized (lock) {
            // 4. 双重检查（Double Check）
            // 在获取锁后，再次检查缓存，因为在排队等待锁的过程中，前一个请求可能已经把数据写入缓存了
            String again = redis.opsForValue().get(pageKey);
            try {
                resp = tryProcessCacheHit(again, id, pageKey, currentUserIdNullable, "page(after-flight)");
            } catch (BusinessException e) {
                // 如果缓存中明确记录了 "NULL"（即内容不存在），则直接抛出异常，不再查库
                singleFlight.remove(pageKey);
                throw e;
            }
            if (resp != null) {
                // 缓存已由其他线程填充，直接返回
                singleFlight.remove(pageKey);
                return resp;
            }

            // 5. 数据库回源查询
            KnowPostDetailRow row = mapper.findDetailById(id);
            
            // 6. 处理内容不存在或已删除的情况
            // 写入 "NULL" 空值缓存，防止缓存穿透（查询不存在的数据导致一直打数据库）
            if (row == null || "deleted".equals(row.getStatus())) {
                redis.opsForValue().set(pageKey, "NULL", java.time.Duration.ofSeconds(30 + java.util.concurrent.ThreadLocalRandom.current().nextInt(31)));
                singleFlight.remove(pageKey);
                throw new BusinessException(ErrorCode.BAD_REQUEST, "内容不存在");
            }

            // 7. 权限校验
            // 公开策略：状态为 published 且可见性为 public 的内容可直接访问
            // 私有策略：否则仅作者本人可见
            boolean isPublic = "published".equals(row.getStatus()) && "public".equals(row.getVisible());
            boolean isOwner = currentUserIdNullable != null && row.getCreatorId() != null && currentUserIdNullable.equals(row.getCreatorId());
            if (!isPublic && !isOwner) {
                singleFlight.remove(pageKey);
                throw new BusinessException(ErrorCode.BAD_REQUEST, "无权限查看");
            }

            // 8. 组装响应对象
            // 解析图片和标签 JSON
            List<String> images = parseStringArray(row.getImgUrls());
            List<String> tags = parseStringArray(row.getTags());
            
            // 此处查询的计数仅作为缓存的基础值，后续 enrich 会刷新
            Map<String, Long> counts = counterService.getCounts("knowpost", String.valueOf(row.getId()), List.of("like", "fav"));
            Long likeCount = counts.getOrDefault("like", 0L);
            Long favoriteCount = counts.getOrDefault("fav", 0L);

            resp = new KnowPostDetailResponse(
                    String.valueOf(row.getId()),
                    row.getTitle(),
                    row.getDescription(),
                    row.getContentUrl(),
                    images,
                    tags,
                    String.valueOf(row.getCreatorId()),
                    row.getAuthorAvatar(),
                    row.getAuthorNickname(),
                    row.getAuthorTagJson(),
                    likeCount,
                    favoriteCount,
                    null, // liked 状态暂时留空，由 enrich 填充
                    null, // faved 状态暂时留空，由 enrich 填充
                    row.getIsTop(),
                    row.getVisible(),
                    row.getType(),
                    row.getPublishTime()
            );

            // 9. 写入 Redis 缓存
            try {
                String json = objectMapper.writeValueAsString(resp);
                int baseTtl = 60;
                // 增加随机抖动（Jitter），防止大量缓存同时过期（雪崩）
                int jitter = ThreadLocalRandom.current().nextInt(30);
                // 根据热度检测结果动态调整 TTL，热点内容缓存时间更长
                int target = hotKey.ttlForPublic(baseTtl, pageKey);
                redis.opsForValue().set(pageKey, json, Duration.ofSeconds(Math.max(target, baseTtl + jitter)));

                // L1 填充
                knowPostDetailCache.put(pageKey, resp);

                log.info("detail source=db key={}", pageKey);
            } catch (Exception ignored) {}

            // 10. 释放锁并返回最终结果
            // 返回前调用 enrich 填充用户维度的 liked/faved 状态
            singleFlight.remove(pageKey);
            return enrichDetailResponse(resp, currentUserIdNullable, false);
        }
    }

    /**
     * 尝试处理缓存命中逻辑。
     *
     * @param cached Redis 中读取的缓存字符串
     * @param id 内容 ID
     * @param pageKey 页面缓存 Key
     * @param uid 当前用户 ID
     * @param sourceLog 日志来源标识
     * @return 若成功处理命中则返回响应对象，否则返回 null
     */
    private KnowPostDetailResponse tryProcessCacheHit(String cached, long id, String pageKey, Long uid, String sourceLog) {
        // 1. 缓存为空，未命中
        if (cached == null) {
            return null;
        }
        
        // 2. 命中空值缓存（防止穿透）
        if ("NULL".equals(cached)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "内容不存在");
        }
        
        try {
            // 3. 反序列化缓存数据
            KnowPostDetailResponse base = objectMapper.readValue(cached, KnowPostDetailResponse.class);

            // L1 填充
            knowPostDetailCache.put(pageKey, base);
            
            // 4. 记录热度并尝试续期
            // 如果该内容正在被高频访问，自动延长其缓存 TTL
            recordHotKeyAndExtendTtl(id, pageKey);
            log.info("detail source={} key={}", sourceLog, pageKey);
            
            // 5. 叠加实时数据（计数与用户状态）并返回
            return enrichDetailResponse(base, uid, true);
        } catch (Exception ignored) {
            // 反序列化失败等异常情况，视为未命中，回源修复
            return null;
        }
    }

    /**
     * 丰富详情响应：叠加实时计数与用户状态。
     *
     * @param base 基础响应对象（来自缓存或 DB）
     * @param uid 当前用户 ID
     * @param refreshCounts 是否需要从 CounterService 刷新计数（缓存命中时需要，DB 回源时不需要）
     * @return 叠加了最新状态的响应对象
     */
    private KnowPostDetailResponse enrichDetailResponse(KnowPostDetailResponse base, Long uid, boolean refreshCounts) {
        Long likeCount = base.likeCount();
        Long favoriteCount = base.favoriteCount();

        // 1. 刷新计数（仅在走缓存时执行）
        // 因为缓存中的计数可能是旧的，权威计数在 CounterService (Redis SDS)
        if (refreshCounts) {
            Map<String, Long> counts = counterService.getCounts("knowpost", base.id(), List.of("like", "fav"));
            if (counts != null) {
                likeCount = counts.getOrDefault("like", likeCount == null ? 0L : likeCount);
                favoriteCount = counts.getOrDefault("fav", favoriteCount == null ? 0L : favoriteCount);
            }
        }

        // 2. 获取用户维度的状态（是否已点赞/收藏）
        // 这部分数据是个性化的，不能存入公共缓存
        Boolean liked = uid != null && counterService.isLiked("knowpost", base.id(), uid);
        Boolean faved = uid != null && counterService.isFaved("knowpost", base.id(), uid);

        // 3. 构造新的 Record 对象返回
        return new KnowPostDetailResponse(
                base.id(),
                base.title(),
                base.description(),
                base.contentUrl(),
                base.images(),
                base.tags(),
                base.authorId(),
                base.authorAvatar(),
                base.authorNickname(),
                base.authorTagJson(),
                likeCount,
                favoriteCount,
                liked,
                faved,
                base.isTop(),
                base.visible(),
                base.type(),
                base.publishTime()
        );
    }

    /**
     * 记录内容热度，并根据热度等级延长相关缓存的 TTL。
     * 延长的缓存包括：
     * 1. 详情页整页缓存 (knowpost:detail:{id})
     * 2. Feed 流内容片段缓存 (feed:item:{id})
     * 这样可以确保热点内容在 Feed 流中也不会轻易过期，避免 Feed 流回源。
     * @param id 内容 ID
     * @param detailPageKey 详情页缓存 Key
     */
    private void recordHotKeyAndExtendTtl(long id, String detailPageKey) {
        // 统一使用 knowpost:{id} 作为热度统计 Key
        String hotKeyId = "knowpost:" + id;
        hotKey.record(hotKeyId);
        
        int baseTtl = 60;
        int target = hotKey.ttlForPublic(baseTtl, hotKeyId);
        
        // 1. 延长详情页缓存
        Long detailTtl = redis.getExpire(detailPageKey);
        if (detailTtl < target) {
            redis.expire(detailPageKey, java.time.Duration.ofSeconds(target));
        }
        
        // 2. 延长 Feed 流内容片段缓存
        String itemKey = "feed:item:" + id;
        Long itemTtl = redis.getExpire(itemKey);
        if (itemTtl < target) {
            redis.expire(itemKey, java.time.Duration.ofSeconds(target));
        }
    }

    private void invalidateCache(long id) {
        String pageKey = "knowpost:detail:" + id + ":v" + DETAIL_LAYOUT_VER;

        redis.delete(pageKey);

        knowPostDetailCache.invalidate(pageKey);
    }

    private List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
