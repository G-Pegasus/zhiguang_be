package com.tongji.counter.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.counter.schema.CounterKeys;
import com.tongji.counter.schema.CounterSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用独立的真实 Redis 实例运行，禁止指向业务 Redis：flush 会扫描 agg:v1:*。
 * mvn -Dcounter.test.redis.port=16379 -Dtest=CounterAggregationRedisTest test
 */
@EnabledIfSystemProperty(named = "counter.test.redis.port", matches = "\\d+")
class CounterAggregationRedisTest {
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static DefaultRedisScript<Long> script;
    private String aggKey;
    private String cntKey;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                System.getProperty("counter.test.redis.host", "127.0.0.1"),
                Integer.parseInt(System.getProperty("counter.test.redis.port")));
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/flush-counter-field.lua"));
        script.setResultType(Long.class);
        assertThat(redis.execute((RedisCallback<String>) connection -> connection.ping())).isEqualTo("PONG");
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @BeforeEach
    void createKeys() {
        String id = UUID.randomUUID().toString();
        aggKey = CounterKeys.aggKey("flush-test", id);
        cntKey = CounterKeys.sdsKey("flush-test", id);
    }

    @AfterEach
    void cleanKeys() {
        redis.delete(List.of(aggKey, cntKey));
    }

    @Test
    void appliesDeltaOnceAndPreservesOtherFieldsAndSlots() {
        byte[] original = counters(100, 1000, 65535, 128, 42);
        writeRaw(original);
        redis.opsForHash().put(aggKey, "1", "10");
        redis.opsForHash().put(aggKey, "2", "3");

        assertThat(flushField("1")).isEqualTo(10L);
        assertThat(readRaw()).isEqualTo(counters(100, 1010, 65535, 128, 42));
        assertThat(redis.opsForHash().entries(aggKey)).containsOnlyKeys("2");
        assertThat(flushField("1")).isZero();
        assertThat(readRaw()).isEqualTo(counters(100, 1010, 65535, 128, 42));
    }

    @Test
    void doesNotCreateZeroSnapshotWhenCounterMissing() {
        redis.opsForHash().put(aggKey, "1", "7");

        assertThat(flushField("1")).isZero();
        // 缺失是读路径触发位图重建的信号；写零值快照会让重建被永久跳过
        assertThat(redis.hasKey(cntKey)).isFalse();
        // 增量保留在桶内，等重建出快照后再折叠，不能丢
        assertThat(redis.opsForHash().get(aggKey, "1")).isEqualTo("7");
    }

    @Test
    void rebuildClearsRetainedIncrementSoLaterFlushDoesNotDoubleCount() {
        redis.opsForHash().put(aggKey, "1", "7");
        assertThat(flushField("1")).isZero();

        // 读路径重建：写回位图真值（已包含这 7 个事实），并清理已折叠字段
        writeRaw(counters(0, 100, 0, 0, 0));
        redis.opsForHash().delete(aggKey, "1");

        assertThat(flushField("1")).isZero();
        assertThat(readCount(1)).isEqualTo(100L);
    }

    @Test
    void handlesMissingAndZeroDeltaWithoutCreatingCounter() {
        assertThat(flushField("1")).isZero();
        redis.opsForHash().put(aggKey, "1", "0");
        assertThat(flushField("1")).isZero();
        assertThat(redis.hasKey(aggKey)).isFalse();
        assertThat(redis.hasKey(cntKey)).isFalse();
    }

    @Test
    void clampsNegativeCountToZero() {
        writeRaw(counters(0, 5, 20, 0, 0));
        redis.opsForHash().put(aggKey, "1", "-8");
        assertThat(flushField("1")).isEqualTo(-8L);
        assertThat(readRaw()).isEqualTo(counters(0, 0, 20, 0, 0));
        assertThat(redis.hasKey(aggKey)).isFalse();
    }

    @Test
    void preservesIncrementArrivingAfterCompletedFlush() {
        writeRaw(counters(0, 0, 0, 0, 0));
        redis.opsForHash().increment(aggKey, "1", 10L);
        flushField("1");
        redis.opsForHash().increment(aggKey, "1", 3L);
        assertThat(readCount(1)).isEqualTo(10L);
        assertThat(redis.opsForHash().get(aggKey, "1")).isEqualTo("3");
        flushField("1");
        assertThat(readCount(1)).isEqualTo(13L);
    }

    @Test
    void invalidCounterLengthLeavesBothKeysUnchangedAndCanBeRetried() {
        byte[] malformed = {1, 2, 3};
        writeRaw(malformed);
        redis.opsForHash().put(aggKey, "1", "10");
        assertThatThrownBy(() -> flushField("1")).hasStackTraceContaining("Invalid counter length");
        assertThat(readRaw()).isEqualTo(malformed);
        assertThat(redis.opsForHash().get(aggKey, "1")).isEqualTo("10");

        writeRaw(counters(0, 100, 0, 0, 0));
        flushField("1");
        assertThat(readCount(1)).isEqualTo(110L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad", "1.5", "1e2", "9007199254740992", "-9007199254740992"})
    void invalidDeltaLeavesBothKeysUnchanged(String delta) {
        byte[] original = counters(0, 100, 0, 0, 0);
        writeRaw(original);
        redis.opsForHash().put(aggKey, "1", delta);
        assertThatThrownBy(() -> flushField("1")).hasStackTraceContaining("Invalid counter delta");
        assertThat(readRaw()).isEqualTo(original);
        assertThat(redis.opsForHash().get(aggKey, "1")).isEqualTo(delta);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "5", "1.5", "01", "like"})
    void invalidFieldLeavesBothKeysUnchanged(String field) {
        redis.opsForHash().put(aggKey, field, "10");
        assertThatThrownBy(() -> flushField(field)).hasStackTraceContaining("Invalid counter field");
        assertThat(redis.hasKey(cntKey)).isFalse();
        assertThat(redis.opsForHash().get(aggKey, field)).isEqualTo("10");
    }

    @Test
    void overflowDoesNotWrapOrConsumeDelta() {
        writeRaw(counters(0, 4294967294L, 0, 0, 0));
        redis.opsForHash().put(aggKey, "1", "1");
        flushField("1");
        assertThat(readCount(1)).isEqualTo(4294967295L);

        redis.opsForHash().put(aggKey, "1", "1");
        assertThatThrownBy(() -> flushField("1")).hasStackTraceContaining("Counter overflow");
        assertThat(readCount(1)).isEqualTo(4294967295L);
        assertThat(redis.opsForHash().get(aggKey, "1")).isEqualTo("1");
    }

    @Test
    void wrongCounterTypeDoesNotConsumeDelta() {
        redis.opsForHash().put(cntKey, "other", "value");
        redis.opsForHash().put(aggKey, "1", "10");
        assertThatThrownBy(() -> flushField("1")).hasStackTraceContaining("WRONGTYPE");
        assertThat(redis.opsForHash().get(cntKey, "other")).isEqualTo("value");
        assertThat(redis.opsForHash().get(aggKey, "1")).isEqualTo("10");
    }

    @Test
    void concurrentFlushesConsumeTheSameDeltaOnlyOnce() throws Exception {
        writeRaw(counters(0, 0, 0, 0, 0));
        redis.opsForHash().put(aggKey, "1", "10");
        var executor = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return flushField("1");
                }));
            }
            start.countDown();
            long applied = 0;
            for (Future<Long> future : futures) applied += future.get(20, TimeUnit.SECONDS);
            assertThat(applied).isEqualTo(10L);
            assertThat(readCount(1)).isEqualTo(10L);
            assertThat(redis.hasKey(aggKey)).isFalse();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentWritersAndConsumerInstancesPreserveAllIncrements() throws Exception {
        writeRaw(counters(0, 0, 0, 0, 0));
        var executor = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int writer = 0; writer < 2; writer++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        redis.opsForHash().increment(aggKey, "1", 1L);
                        redis.opsForHash().increment(aggKey, "2", 1L);
                    }
                    return null;
                }));
            }
            for (int instance = 0; instance < 2; instance++) {
                var consumer = new CounterAggregationConsumer(new ObjectMapper(), redis);
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 60; i++) consumer.flush();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
            new CounterAggregationConsumer(new ObjectMapper(), redis).flush();
            assertThat(readCount(1)).isEqualTo(400L);
            assertThat(readCount(2)).isEqualTo(400L);
            assertThat(redis.hasKey(aggKey)).isFalse();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Long flushField(String field) {
        return redis.execute(script, List.of(aggKey, cntKey),
                String.valueOf(CounterSchema.SCHEMA_LEN), String.valueOf(CounterSchema.FIELD_SIZE), field);
    }

    private static byte[] counters(long... values) {
        ByteBuffer buffer = ByteBuffer.allocate(CounterSchema.SCHEMA_LEN * CounterSchema.FIELD_SIZE);
        for (long value : values) buffer.putInt((int) value);
        return buffer.array();
    }

    private void writeRaw(byte[] value) {
        redis.execute((RedisCallback<Boolean>) connection ->
                connection.stringCommands().set(cntKey.getBytes(StandardCharsets.UTF_8), value));
    }

    private byte[] readRaw() {
        return redis.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(cntKey.getBytes(StandardCharsets.UTF_8)));
    }

    private long readCount(int idx) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(readRaw()).getInt(idx * CounterSchema.FIELD_SIZE));
    }
}
