package com.tongji.knowpost.listener;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tongji.counter.event.CounterEvent;
import com.github.benmanes.caffeine.cache.Cache;
import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.model.KnowPost;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * Feed 页面缓存失效与计数旁路更新监听器。
 *
 * <p>职责：</p>
 * - 监听点赞/收藏等计数事件（仅处理实体类型为 "knowpost"）；
 * - 根据“页面反向索引”（`feed:public:index:{eid}:{hour}`）定位受影响页面，
 *   同步更新本地 Caffeine 缓存与 Redis 页面 JSON（保持 TTL 不变）；
 * - 同步创作者收到的点赞/收藏用户维度计数（UserCounterService）。
 *
 * <p>设计要点：</p>
 * - preserveUserFlags=true 时仅更新本地缓存并保留用户态标志 liked/faved，
 *   写回 Redis 页面 JSON 时不携带用户态标志，避免污染共享缓存；
 * - 页面 JSON 写回前读取并沿用剩余 TTL，防止覆盖过期策略；
 * - 反向索引按小时维护，监听器会同时覆盖当前与上一个小时段的页面键。
 */
@Component
public class FeedCacheInvalidationListener {

    private final Cache<String, FeedPageResponse> feedPublicCache;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final com.tongji.counter.service.UserCounterService userCounterService;
    private final com.tongji.knowpost.mapper.KnowPostMapper knowPostMapper;

    public FeedCacheInvalidationListener(@Qualifier("feedPublicCache") Cache<String, FeedPageResponse> feedPublicCache,
                                         StringRedisTemplate redis,
                                         ObjectMapper objectMapper,
                                         com.tongji.counter.service.UserCounterService userCounterService,
                                         com.tongji.knowpost.mapper.KnowPostMapper knowPostMapper) {
        this.feedPublicCache = feedPublicCache;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.userCounterService = userCounterService;
        this.knowPostMapper = knowPostMapper;
    }

    /**
     * 监听计数事件并进行缓存更新。
     *
     * <p>流程：</p>
     * - 仅处理实体类型为 "knowpost" 的 like/fav 事件；
     * - 若可解析到内容的创作者 ID，则同步其“收到的点赞/收藏”计数；
     * - 通过最近两小时的反向索引集合定位受影响页面：
     *   - 更新本地 Caffeine 页缓存（保留 liked/faved 标志）；
     *   - 更新 Redis 页缓存（不携带用户态标志，保持 TTL）。
     * - 若某页面键在 Redis 未命中，则清理其索引引用，降低键空间噪音。
     */
    @EventListener
    public void onCounterChanged(CounterEvent event) {
        if (!"knowpost".equals(event.getEntityType())) {
            return;
        }

        String metric = event.getMetric();
        if ("like".equals(metric) || "fav".equals(metric)) {
            String eid = event.getEntityId();
            int delta = event.getDelta();

            try {
                KnowPost post = knowPostMapper.findById(Long.valueOf(eid));
                if (post != null && post.getCreatorId() != null) {
                    long owner = post.getCreatorId();
                    if ("like".equals(metric)) {
                        userCounterService.incrementLikesReceived(owner, delta);
                    }
                    if ("fav".equals(metric)) {
                        userCounterService.incrementFavsReceived(owner, delta);
                    }
                }
            } catch (Exception ignored) {
            }

            long hourSlot = System.currentTimeMillis() / 3600000L;
            Set<String> keys = new LinkedHashSet<>();
            Set<String> cur = redis.opsForSet().members("feed:public:index:" + eid + ":" + hourSlot);
            if (cur != null) {
                keys.addAll(cur);
            }

            Set<String> prev = redis.opsForSet().members("feed:public:index:" + eid + ":" + (hourSlot - 1));
            if (prev != null) {
                keys.addAll(prev);
            }
            if (keys.isEmpty()) {
                return;
            }

            for (String key : keys) {
                FeedPageResponse local = feedPublicCache.getIfPresent(key);
                if (local != null) {
                    FeedPageResponse updatedLocal = adjustPageCounts(local, eid, metric, delta, true);
                    feedPublicCache.put(key, updatedLocal);
                }

                String cached = redis.opsForValue().get(key);
                if (cached != null) {
                    try {
                        FeedPageResponse resp = objectMapper.readValue(cached, FeedPageResponse.class);
                        FeedPageResponse updated = adjustPageCounts(resp, eid, metric, delta, false);
                        writePageJsonKeepingTtl(key, updated);
                    } catch (Exception ignored) {}
                } else {
                    redis.opsForSet().remove("feed:public:index:" + eid + ":" + hourSlot, key);
                }
            }
        }
    }

    /**
     * 调整页面快照中的目标内容计数。
     *
     * <p>行为：</p>
     * - 遍历页面 items，定位 id==eid 的项并更新 like/fav；
     * - preserveUserFlags=true：保留 liked/faved 标志用于本地缓存；
     * - preserveUserFlags=false：写回 Redis 页面 JSON 时不携带用户态标志；
     * - 返回新的页面响应快照。
     */
    private FeedPageResponse adjustPageCounts(FeedPageResponse page, String eid, String metric, int delta, boolean preserveUserFlags) {
        List<FeedItemResponse> items = new ArrayList<>(page.items().size());
        for (FeedItemResponse it : page.items()) {
                if (eid.equals(it.id())) {
                    Long like = it.likeCount();
                    Long fav = it.favoriteCount();

                    if ("like".equals(metric)) {
                        like = Math.max(0L, (like == null ? 0L : like) + delta);
                    }
                    if ("fav".equals(metric)) {
                        fav = Math.max(0L, (fav == null ? 0L : fav) + delta);
                    }

                    Boolean liked = preserveUserFlags ? it.liked() : null;
                    Boolean faved = preserveUserFlags ? it.faved() : null;

                    it = new FeedItemResponse(
                            it.id(),
                            it.title(),
                            it.description(),
                            it.coverImage(),
                            it.tags(),
                            it.authorAvatar(),
                            it.authorNickname(),
                            it.tagJson(),
                            like,
                            fav,
                            liked,
                            faved,
                            it.isTop()
                    );
                }
                items.add(it);
            }

        return new FeedPageResponse(items, page.page(), page.size(), page.hasMore());
    }

    /**
     * 写回页面 JSON 并保留原 TTL。
     *
     * <p>目的：</p>
     * - 保持页面缓存的过期策略一致，避免因覆盖写导致 TTL 重置；
     * - 若键未设置 TTL，则直接写入最新 JSON。
     */
    private void writePageJsonKeepingTtl(String key, FeedPageResponse page) {
        try {
            String json = objectMapper.writeValueAsString(page);
            long ttl = redis.getExpire(key);
            if (ttl > 0) {
                redis.opsForValue().set(key, json, java.time.Duration.ofSeconds(ttl));
            } else {
                redis.opsForValue().set(key, json);
            }
        } catch (Exception ignored) {}
    }
}
