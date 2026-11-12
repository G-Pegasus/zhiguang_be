package com.tongji.counter.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.counter.schema.CounterKeys;
import com.tongji.counter.schema.CounterSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.List;

/**
 * 计数事件聚合与刷写消费者。
 *
 * <p>职责：</p>
 * - 消费点赞/收藏等增量事件，写入 Redis 聚合桶（Hash）；
 * - 以固定延迟定时任务将聚合增量折叠到 SDS 固定结构计数；
 * - 在同一 Lua 中读取增量、更新总计数并删除字段，避免并发刷写重复加算；
 * - 总计数缺失时不做零值初始化：保留增量，等待读路径按位图重建后再折叠。
 */
@Service
@Slf4j
public class CounterAggregationConsumer {

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> flushScript;

    // 使用 Redis Hash 作为持久化聚合桶：agg:{schema}:{etype}:{eid} ，field=idx ，value=delta
    public CounterAggregationConsumer(ObjectMapper objectMapper, StringRedisTemplate redis) {
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.flushScript = new DefaultRedisScript<>();
        this.flushScript.setResultType(Long.class);
        this.flushScript.setLocation(new ClassPathResource("redis/flush-counter-field.lua"));
    }

    /**
     * 消费计数事件并写入聚合桶。
     * @param message 事件 JSON
     * @param ack 位点确认对象（手动提交）
     */
    @KafkaListener(topics = CounterTopics.EVENTS, groupId = "counter-agg")
    public void onMessage(String message, Acknowledgment ack) throws Exception {
        CounterEvent evt = objectMapper.readValue(message, CounterEvent.class);
        String aggKey = CounterKeys.aggKey(evt.getEntityType(), evt.getEntityId());
        String field = String.valueOf(evt.getIdx());
        try {
            // 将增量持久化到 Redis Hash
            redis.opsForHash().increment(aggKey, field, evt.getDelta());
            // 成功后提交位点，绑定“已持久化”语义
            ack.acknowledge();
        } catch (Exception ex) {
            // 不提交位点以便重试
        }
    }

    /**
     * 将聚合增量刷写到 SDS 固定结构计数。
     * 每轮完成后延迟 1s；增量必须在 Lua 内读取，避免多个实例使用同一份旧值。
     */
    @Scheduled(fixedDelay = 1000L)
    public void flush() {
        // 简化实现：扫描所有聚合桶键（生产建议使用索引集合替代 KEYS）
        Set<String> keys = redis.keys("agg:" + CounterSchema.SCHEMA_ID + ":*");
        if (keys == null || keys.isEmpty()) {
            return;
        }

        for (String aggKey : keys) {
            // 解析 etype/eid 以定位 SDS key
            String[] parts = aggKey.split(":", 4); // agg:schema:etype:eid
            if (parts.length < 4) {
                continue;
            }

            String cntKey = CounterKeys.sdsKey(parts[2], parts[3]);

            // 只枚举字段，不在 Java 中读取 delta；新字段可在下一轮处理。
            for (Object entry : redis.opsForHash().keys(aggKey)) {
                String field = String.valueOf(entry);
                try {
                    redis.execute(flushScript, List.of(aggKey, cntKey),
                            String.valueOf(CounterSchema.SCHEMA_LEN),
                            String.valueOf(CounterSchema.FIELD_SIZE),
                            field);
                } catch (Exception ex) {
                    // 校验失败保留增量；若响应丢失，重试也只会读取尚未处理的新增量。
                    log.warn("计数刷写失败，保留未处理增量: aggKey={}, field={}", aggKey, field, ex);
                }
            }
            // HDEL 删除最后一个字段时 Redis 自动移除 Hash，不再另行判断后 DEL。
        }
    }
}
