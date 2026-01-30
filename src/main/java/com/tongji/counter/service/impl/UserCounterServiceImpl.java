package com.tongji.counter.service.impl;

import com.tongji.counter.schema.UserCounterKeys;
import com.tongji.counter.service.CounterService;
import com.tongji.counter.service.UserCounterService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.relation.mapper.RelationMapper;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户维度计数服务实现。
 *
 * <p>职责：</p>
 * - 异步维护关注/粉丝/发文/获赞/获收藏计数（SDS）；
 * - 提供按需重建能力以纠偏异常；
 * - 重建过程聚合作者所有内容的获赞/获收藏总数。
 */
@Service
public class UserCounterServiceImpl implements UserCounterService {
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> incrScript;
    private final KnowPostMapper knowPostMapper;
    private final CounterService counterService;
    private final RelationMapper relationMapper;

    public UserCounterServiceImpl(StringRedisTemplate redis,
                                  KnowPostMapper knowPostMapper,
                                  CounterService counterService,
                                  RelationMapper relationMapper) {
        this.redis = redis;
        this.knowPostMapper = knowPostMapper;
        this.counterService = counterService;
        this.relationMapper = relationMapper;
        this.incrScript = new DefaultRedisScript<>();
        this.incrScript.setResultType(Long.class);
        // 用户维度计数原子折叠（1 基坐标）
        this.incrScript.setScriptText(INCR_FIELD_LUA);
    }

    /** 增量更新关注数 */
    @Override
    public void incrementFollowings(long userId, int delta) {
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "1", String.valueOf(delta));
    }

    /** 增量更新粉丝数 */
    @Override
    public void incrementFollowers(long userId, int delta) {
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "2", String.valueOf(delta));
    }

    /** 增量更新发文数 */
    @Override
    public void incrementPosts(long userId, int delta) {
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "3", String.valueOf(delta));
    }

    /** 增量更新获赞数（作者维度） */
    @Override
    public void incrementLikesReceived(long userId, int delta) {
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "4", String.valueOf(delta));
    }

    /** 增量更新获收藏数（作者维度） */
    @Override
    public void incrementFavsReceived(long userId, int delta) {
        String key = UserCounterKeys.sdsKey(userId);
        redis.execute(incrScript, List.of(key), "5", "4", "5", String.valueOf(delta));
    }

    /** 基于事实重建全部用户维度计数 */
    @Override
    public void rebuildAllCounters(long userId) {
        String key = UserCounterKeys.sdsKey(userId);
        byte[] raw = redis.execute((RedisCallback<byte[]>) c -> c.stringCommands().get(key.getBytes(StandardCharsets.UTF_8)));
        int len = 5 * 4;
        byte[] buf = new byte[len];
        if (raw != null && raw.length == len) {
            // 保留已存在的值，按需覆盖
            System.arraycopy(raw, 0, buf, 0, len);
        }
        // 从数据库读取 关注数、粉丝数
        long followings = relationMapper.countFollowingActive(userId);
        long followers = relationMapper.countFollowerActive(userId);

        long posts;
        List<Long> ids = knowPostMapper.listMyPublishedIds(userId);
        // 将 ids 转换成字符串类型的 List
        List<String> idStr = ids.stream()
                .map(String::valueOf)
                .collect(Collectors.toList());

        if (!idStr.isEmpty()) {
            posts = idStr.size();
            long likeSum = 0L;
            long favSum = 0L;
            Map<String, Map<String, Long>> counts = counterService.getCountsBatch("knowpost", idStr, List.of("like", "fav"));
            for (String id : idStr) { // 聚合作者全部知文的获赞/获收藏总数
                Map<String, Long> v = counts.get(id);
                likeSum += v.getOrDefault("like", 0L);
                favSum += v.getOrDefault("fav", 0L);
            }
            write32be(buf, 2 * 4, posts);
            write32be(buf, 3 * 4, likeSum);
            write32be(buf, 4 * 4, favSum);
        } else {
            write32be(buf, 2 * 4, 0L);
            write32be(buf, 3 * 4, 0L);
            write32be(buf, 4 * 4, 0L);
        }
        write32be(buf, 0, followings);
        write32be(buf, 4, followers);

        // 回写用户计数 SDS
        redis.execute((RedisCallback<Void>) c -> {
            c.stringCommands().set(key.getBytes(StandardCharsets.UTF_8), buf);
            return null;
        });
    }

    private static final String INCR_FIELD_LUA = """
            
            local cntKey = KEYS[1]
            local schemaLen = tonumber(ARGV[1])
            local fieldSize = tonumber(ARGV[2])
            local idx = tonumber(ARGV[3])
            local delta = tonumber(ARGV[4])
            local function read32be(s, off)
              local b = {string.byte(s, off+1, off+4)}
              local n = 0
              for i=1,4 do n = n * 256 + b[i] end
              return n
            end
            local function write32be(n)
              local t = {}
              for i=4,1,-1 do t[i] = n % 256; n = math.floor(n/256) end
              return string.char(unpack(t))
            end
            local cnt = redis.call('GET', cntKey)
            if not cnt then cnt = string.rep(string.char(0), schemaLen * fieldSize) end
            local off = (idx - 1) * fieldSize
            local v = read32be(cnt, off) + delta
            if v < 0 then v = 0 end
            local seg = write32be(v)
            cnt = string.sub(cnt, 1, off) .. seg .. string.sub(cnt, off+fieldSize+1)
            redis.call('SET', cntKey, cnt)
            return 1
            """;

    private static long read32be(byte[] buf, int off) {
        if (buf == null || buf.length < off + 4) return 0L;
        long n = 0L;
        for (int i = 0; i < 4; i++) n = (n << 8) | (buf[off + i] & 0xFFL);
        return n;
    }

    private static void write32be(byte[] buf, int off, long val) {
        long n = Math.max(0, Math.min(val, 0xFFFF_FFFFL));
        buf[off] = (byte) ((n >>> 24) & 0xFF);
        buf[off + 1] = (byte) ((n >>> 16) & 0xFF);
        buf[off + 2] = (byte) ((n >>> 8) & 0xFF);
        buf[off + 3] = (byte) (n & 0xFF);
    }
}

