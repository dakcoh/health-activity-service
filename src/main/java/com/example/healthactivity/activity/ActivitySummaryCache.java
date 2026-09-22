package com.example.healthactivity.activity;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
class ActivitySummaryCache {
    private static final Duration TTL = Duration.ofMinutes(5);
    private final StringRedisTemplate redis;
    private final JsonMapper json;

    ActivitySummaryCache(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    List<ActivitySummary> get(String recordKey, LocalDate from, LocalDate toExclusive, boolean monthly) {
        try {
            String cached = (String) redis.opsForHash().get(key(recordKey, monthly), field(from, toExclusive));
            return cached == null ? null : Arrays.asList(json.readValue(cached, ActivitySummary[].class));
        } catch (DataAccessException | JacksonException exception) {
            return null; // 캐시 장애나 손상된 값은 DB 조회로 복구한다.
        }
    }

    void put(String recordKey, LocalDate from, LocalDate toExclusive, boolean monthly, List<ActivitySummary> rows) {
        String key = key(recordKey, monthly);
        try {
            redis.opsForHash().put(key, field(from, toExclusive), json.writeValueAsString(rows));
            if (!Boolean.TRUE.equals(redis.expire(key, TTL))) redis.delete(key);
        } catch (DataAccessException | JacksonException exception) {
            // 만료 시간 설정에 실패했다면 오래 남을 수 있는 항목을 가능한 한 제거한다.
            try {
                redis.delete(key);
            } catch (DataAccessException ignored) {
                // Redis 장애는 DB 조회 결과에 영향을 주지 않는다.
            }
        }
    }

    void evict(String recordKey) {
        try {
            redis.delete(List.of(key(recordKey, false), key(recordKey, true)));
        } catch (DataAccessException exception) {
            // Redis 장애 중에도 DB 커밋 결과를 유지한다. TTL이 오래된 값을 제한한다.
        }
    }

    private static String key(String recordKey, boolean monthly) {
        return "health-activity:v1:" + (monthly ? "monthly:" : "daily:") + recordKey;
    }

    private static String field(LocalDate from, LocalDate toExclusive) {
        return from + ":" + toExclusive;
    }
}
