package com.example.healthactivity;

import com.example.healthactivity.activity.ActivityRecord;
import com.example.healthactivity.activity.ActivityRepository;
import com.example.healthactivity.activity.ActivityRequest;
import com.example.healthactivity.activity.ActivitySummary;
import com.example.healthactivity.member.Member;
import com.example.healthactivity.member.MemberRepository;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActivityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired ActivityRepository activities;
    @Autowired JdbcTemplate jdbc;
    private final JsonMapper json = new JsonMapper();
    private Member member;

    private static final String ENTRY = """
            {"period":{"from":"2024-11-15 00:00:00","to":"2024-11-15 00:10:00"},
             "steps":54,"distance":{"unit":"km","value":0.04223},
             "calories":{"unit":"kcal","value":2.03}}
            """;

    @BeforeEach
    void setUp() {
        clean();
        member = members.saveAndFlush(new Member("홍길동", "walker", "walker@example.com", "unused"));
    }

    @AfterEach
    void clean() {
        activities.deleteAll();
        members.deleteAll();
    }

    private String body(String... entries) {
        return """
                {"recordkey":"%s","type":"steps","lastUpdate":"2024-12-16 14:40:00 +0000",
                 "data":{"source":{"name":"SamsungHealth","mode":9,"type":"",
                    "product":{"name":"Android","vender":"Samsung"}},"entries":[%s]}}
                """.formatted(member.getRecordKey(), String.join(",", entries));
    }

    private MvcResult send(String body, int status) throws Exception {
        return mvc.perform(post("/api/activities").with(user(member.getEmail())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status)).andReturn();
    }

    @Test
    void storesUtcAndPreservesDecimalsAndSourceMetadata() throws Exception {
        send(body(ENTRY), 200);
        var stored = activities.findAll().get(0);
        assertThat(stored.getPeriodFrom()).isEqualTo(LocalDateTime.of(2024, 11, 14, 15, 0));
        assertThat(stored.getPeriodTo()).isEqualTo(LocalDateTime.of(2024, 11, 14, 15, 10));
        assertThat(jdbc.queryForObject("select period_from from activity_records", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2024, 11, 14, 15, 0));
        assertThat(stored.getSteps()).isEqualByComparingTo("54");
        assertThat(stored.getDistance()).isEqualByComparingTo("0.04223");
        assertThat(jdbc.queryForObject("select product_vendor from activity_records", String.class)).isEqualTo("Samsung");
        assertThat(jdbc.queryForObject("select last_update from activity_records", LocalDateTime.class))
                .isEqualTo(LocalDateTime.of(2024, 12, 16, 14, 40));
    }

    @Test
    void appleFractionalStringAndZeroDurationAreAccepted() throws Exception {
        String entry = ENTRY.replace("2024-11-15 00:00:00", "2024-11-15T00:00:00+0000")
                .replace("2024-11-15 00:10:00", "2024-11-15T00:00:00+0000")
                .replace("\"steps\":54", "\"steps\":\"0.07427087853201997555\"")
                .replace("\"value\":2.03", "\"value\":0");
        send(body(entry).replace("SamsungHealth", "Health Kit").replace("\"mode\":9", "\"mode\":10"), 200);
        var stored = activities.findAll().get(0);
        assertThat(stored.getSteps()).isEqualByComparingTo("0.07427087853201997555");
        assertThat(stored.getCalories()).isZero();
        assertThat(stored.getPeriodFrom()).isEqualTo(LocalDateTime.of(2024, 11, 15, 0, 0));
        assertThat(stored.getPeriodTo()).isEqualTo(stored.getPeriodFrom());
    }

    @Test
    void deduplicatesWithinRequestAndAcrossRetries() throws Exception {
        var first = json.readTree(send(body(ENTRY, ENTRY), 200).getResponse().getContentAsString());
        assertThat(first.get("inserted").intValue()).isEqualTo(1);
        assertThat(first.get("duplicates").intValue()).isEqualTo(1);
        var second = json.readTree(send(body(ENTRY.replace("\"steps\":54", "\"steps\":\"54.00\"")), 200)
                .getResponse().getContentAsString());
        assertThat(second.get("inserted").intValue()).isZero();
        assertThat(second.get("duplicates").intValue()).isEqualTo(1);
        assertThat(activities.count()).isEqualTo(1);
    }

    @Test
    void conflictRollsBackWholeRequest() throws Exception {
        send(body(ENTRY), 200);
        send(body(ENTRY.replace("2024-11-15", "2024-11-16"), ENTRY.replace("\"steps\":54", "\"steps\":55")), 409);
        assertThat(activities.count()).isEqualTo(1);
        assertThat(activities.findAll().get(0).getSteps()).isEqualByComparingTo("54");
        activities.deleteAll();
        send(body(ENTRY, ENTRY.replace("\"steps\":54", "\"steps\":55")), 409);
        assertThat(activities.count()).isZero();
    }

    @Test
    void rejectsInvalidValuesWithoutPartialWrites() throws Exception {
        for (String bad : new String[] {
                ENTRY.replace("\"steps\":54", "\"steps\":-1"),
                ENTRY.replace("\"steps\":54", "\"steps\":\"NaN\""),
                ENTRY.replace("\"steps\":54", "\"steps\":0.000000000000000000001"),
                ENTRY.replace("\"steps\":54", "\"steps\":1000000000000000000"),
                ENTRY.replace("2024-11-15 00:10:00", "2024-11-14 23:59:00"),
                ENTRY.replace("2024-11-15", "2024-02-30"),
                ENTRY.replace("\"km\"", "\"m\""),
                ENTRY.replace("\"calories\":{\"unit\":\"kcal\",\"value\":2.03}", "\"calories\":null"),
                "null" }) {
            send(body(ENTRY, bad), 400);
            assertThat(activities.count()).isZero();
        }
        send(body(), 400);
        send(body(ENTRY).replace("\"mode\":9", "\"mode\":10"), 400);
        send(body(ENTRY).replace("\"type\":\"steps\"", "\"type\":\"sleep\""), 400);
        send(body(ENTRY).replace("2024-12-16 14:40:00 +0000", "invalid"), 400);
    }

    @Test
    void rejectsOtherMembersAndUnauthenticatedRequests() throws Exception {
        var other = members.saveAndFlush(new Member("다른 회원", "other", "other@example.com", "unused"));
        send(body(ENTRY).replace(member.getRecordKey(), other.getRecordKey()), 403);
        mvc.perform(post("/api/activities").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(ENTRY)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/activities").with(user(member.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(ENTRY)))
                .andExpect(status().isForbidden());
        assertThat(activities.count()).isZero();
    }

    @Test
    void concurrentRetriesInsertOnlyOnce() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        String payload = body(ENTRY);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++) tasks.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return json.readTree(send(payload, 200).getResponse().getContentAsString()).get("inserted").intValue();
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(tasks.get(0).get(20, TimeUnit.SECONDS) + tasks.get(1).get(20, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(activities.count()).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void uniqueConstraintProtectsDirectDatabaseWrites() throws Exception {
        send(body(ENTRY), 200);
        assertThatThrownBy(() -> jdbc.update("""
                insert into activity_records (record_key, source_name, source_mode, source_type, product_name,
                  product_vendor, period_from, period_to, steps, calories, distance, last_update, created_at)
                select record_key, source_name, source_mode, source_type, product_name, product_vendor,
                  period_from, period_to, steps, calories, distance, last_update, created_at from activity_records
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "HEALTH_SAMPLE_DIR", matches = ".+")
    void importsExternalSamplesWithoutCommittingThem() throws Exception {
        for (int i = 1; i <= 4; i++) {
            activities.deleteAll();
            String payload = Files.readString(Path.of(System.getenv("HEALTH_SAMPLE_DIR"), "INPUT_DATA" + i + ".json"));
            ActivityRequest input = json.readValue(payload, ActivityRequest.class);
            // 외부 키의 소유권은 테스트 준비 단계에서만 지정한다. 공개 API로 임의의 키를 연결하지 않는다.
            jdbc.update("update members set record_key = ? where id = ?", input.recordkey(), member.getId());
            var receipt = json.readTree(send(payload, 200).getResponse().getContentAsString());
            assertThat(receipt.get("inserted").intValue()).isEqualTo(input.data().entries().size());
            assertThat(activities.count()).isEqualTo(input.data().entries().size());
            var saved = activities.findAll();
            assertThat(saved.stream().map(ActivityRecord::getSteps).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo(input.data().entries().stream().map(ActivityRequest.Entry::steps)
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
            assertThat(saved.stream().map(ActivityRecord::getDistance).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo(input.data().entries().stream().map(e -> e.distance().value())
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
            assertThat(saved.stream().map(ActivityRecord::getCalories).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo(input.data().entries().stream().map(e -> e.calories().value())
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
            var retried = json.readTree(send(payload, 200).getResponse().getContentAsString());
            assertThat(retried.get("inserted").intValue()).isZero();
            assertThat(retried.get("duplicates").intValue()).isEqualTo(input.data().entries().size());
            assertSampleSummaries(input, false);
            assertSampleSummaries(input, true);
        }
    }

    private ActivitySummary[] summaries(String granularity, String key, String from, String to) throws Exception {
        var result = mvc.perform(get("/api/activities/" + granularity).with(user(member.getEmail()))
                        .param("recordKey", key).param("from", from).param("to", to))
                .andExpect(status().isOk()).andReturn();
        return json.readValue(result.getResponse().getContentAsString(), ActivitySummary[].class);
    }

    private void assertSampleSummaries(ActivityRequest input, boolean monthly) throws Exception {
        var expected = new java.util.TreeMap<String, BigDecimal[]>();
        for (var entry : input.data().entries()) {
            // 저장·조회 코드 대신 원본의 현지 날짜에서 기대 결과를 계산한다.
            String day = input.data().source().name().equals("SamsungHealth") ? entry.period().from().substring(0, 10)
                    : java.time.OffsetDateTime.parse(entry.period().from(),
                            java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXX"))
                            .atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().toString();
            String period = monthly ? day.substring(0, 7) : day;
            var totals = expected.computeIfAbsent(period,
                    ignored -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO });
            totals[0] = totals[0].add(entry.steps());
            totals[1] = totals[1].add(entry.calories().value());
            totals[2] = totals[2].add(entry.distance().value());
        }
        var actual = summaries(monthly ? "monthly" : "daily", input.recordkey(),
                monthly ? "2024-11" : "2024-11-01", monthly ? "2024-12" : "2024-12-31");
        assertThat(actual).extracting(ActivitySummary::period).containsExactlyElementsOf(expected.keySet());
        for (var row : actual) {
            var totals = expected.get(row.period());
            assertThat(row.recordkey()).isEqualTo(input.recordkey());
            assertThat(row.steps()).isEqualTo(totals[0].setScale(0, java.math.RoundingMode.HALF_UP).toBigIntegerExact());
            assertThat(row.calories()).isEqualByComparingTo(totals[1]);
            assertThat(row.distance()).isEqualByComparingTo(totals[2]);
        }
    }

    private String entry(String from, String to, String steps) {
        return ENTRY.replace("2024-11-15 00:00:00", from).replace("2024-11-15 00:10:00", to)
                .replace("\"steps\":54", "\"steps\":" + steps);
    }

    @Test
    void dailyBoundsUseKoreanMidnightAndRoundAfterSumming() throws Exception {
        send(body(entry("2024-11-30 23:59:59", "2024-12-01 00:10:00", "9"),
                entry("2024-12-01 00:00:00", "2024-12-01 00:10:00", "0.4"),
                entry("2024-12-01 23:59:59", "2024-12-02 00:10:00", "0.4"),
                entry("2024-12-02 00:00:00", "2024-12-02 00:10:00", "0.4")), 200);
        var daily = summaries("daily", member.getRecordKey(), "2024-12-01", "2024-12-01");
        assertThat(daily).hasSize(1);
        assertThat(daily[0].period()).isEqualTo("2024-12-01");
        assertThat(daily[0].steps()).isEqualTo(java.math.BigInteger.ONE);
        assertThat(daily[0].calories()).isEqualByComparingTo("4.06");
        assertThat(daily[0].distance()).isEqualByComparingTo("0.08446");
        var monthly = summaries("monthly", member.getRecordKey(), "2024-11", "2024-12");
        assertThat(monthly).extracting(ActivitySummary::period).containsExactly("2024-11", "2024-12");
        assertThat(monthly[0].steps()).isEqualTo(java.math.BigInteger.valueOf(9));
        assertThat(monthly[1].steps()).isEqualTo(java.math.BigInteger.ONE);
    }

    @Test
    void monthlyRoundsRawTotalsRatherThanRoundedDaysAndSupportsLargeIntegers() throws Exception {
        send(body(entry("2024-02-28 10:00:00", "2024-02-28 10:10:00", "0.4"),
                entry("2024-02-29 10:00:00", "2024-02-29 10:10:00", "0.4"),
                entry("2024-03-01 00:00:00", "2024-03-01 00:10:00", "3000000000")), 200);
        assertThat(summaries("daily", member.getRecordKey(), "2024-02-28", "2024-02-29"))
                .extracting(ActivitySummary::steps).containsExactly(java.math.BigInteger.ZERO, java.math.BigInteger.ZERO);
        var rows = summaries("monthly", member.getRecordKey(), "2024-02", "2024-03");
        assertThat(rows[0].steps()).isEqualTo(java.math.BigInteger.ONE);
        assertThat(rows[1].steps()).isEqualTo(new java.math.BigInteger("3000000000"));
        assertThat(summaries("daily", member.getRecordKey(), "2024-04-01", "2024-04-30")).isEmpty();
    }

    @Test
    void appleUtcYearBoundaryAndOtherMembersAreHandled() throws Exception {
        String apple = body(entry("2024-12-31T15:00:00+0000", "2024-12-31T15:10:00+0000", "1.5"))
                .replace("SamsungHealth", "Health Kit").replace("\"mode\":9", "\"mode\":10");
        send(apple, 200);
        var other = members.saveAndFlush(new Member("다른 회원", "other", "other@example.com", "unused"));
        mvc.perform(post("/api/activities").with(user(other.getEmail())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(apple.replace(member.getRecordKey(), other.getRecordKey()))).andExpect(status().isOk());
        assertThat(summaries("monthly", member.getRecordKey(), "2024-12", "2024-12")).isEmpty();
        var january = summaries("monthly", member.getRecordKey(), "2025-01", "2025-01");
        assertThat(january).hasSize(1);
        assertThat(january[0].steps()).isEqualTo(java.math.BigInteger.valueOf(2));
        for (String endpoint : new String[] { "daily", "monthly" }) {
            String date = endpoint.equals("daily") ? "2025-01-01" : "2025-01";
            mvc.perform(get("/api/activities/" + endpoint).with(user(member.getEmail()))
                    .param("recordKey", other.getRecordKey()).param("from", date).param("to", date))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/activities/" + endpoint)
                    .param("recordKey", member.getRecordKey()).param("from", date).param("to", date))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void invalidQueryRangesAreRejectedAndLimitsAreInclusive() throws Exception {
        for (String[] range : new String[][] {
                {"daily", "2024-02-30", "2024-03-01"}, {"daily", "2024-02-01", "2024-01-01"},
                {"daily", "2024-01-01", "2025-01-01"}, {"daily", "2024-1-1", "2024-01-01"},
                {"monthly", "2024-13", "2024-13"}, {"monthly", "2024-02", "2024-01"},
                {"monthly", "2024-01", "2026-01"}, {"monthly", "9999-12", "9999-12"},
                {"daily", "", "2024-01-01"} }) {
            mvc.perform(get("/api/activities/" + range[0]).with(user(member.getEmail()))
                            .param("recordKey", member.getRecordKey()).param("from", range[1]).param("to", range[2]))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("status").value(400));
        }
        assertThat(summaries("daily", member.getRecordKey(), "2024-01-01", "2024-12-31")).isEmpty();
        assertThat(summaries("monthly", member.getRecordKey(), "2024-01", "2025-12")).isEmpty();
        mvc.perform(get("/api/activities/daily").with(user(member.getEmail())).param("recordKey", member.getRecordKey()))
                .andExpect(status().isBadRequest());
    }
}
