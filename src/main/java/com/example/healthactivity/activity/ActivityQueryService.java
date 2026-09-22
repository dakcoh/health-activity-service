package com.example.healthactivity.activity;

import com.example.healthactivity.member.MemberRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class ActivityQueryService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final ActivityRepository activities;
    private final MemberRepository members;
    private final ActivitySummaryCache cache;

    public ActivityQueryService(ActivityRepository activities, MemberRepository members, ActivitySummaryCache cache) {
        this.activities = activities;
        this.members = members;
        this.cache = cache;
    }

    public List<ActivitySummary> daily(String email, String recordKey, String from, String to) {
        try {
            if (!from.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !to.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                throw invalid("일간 날짜 형식은 yyyy-MM-dd입니다.");
            }
            LocalDate start = LocalDate.parse(from);
            LocalDate end = LocalDate.parse(to);
            validateRange(start, end);
            if (ChronoUnit.DAYS.between(start, end) >= 366) throw invalid("일간 조회는 최대 366일입니다.");
            return summarize(email, recordKey, start, end.plusDays(1), false);
        } catch (DateTimeException exception) {
            throw invalid("유효하지 않은 날짜입니다.");
        }
    }

    public List<ActivitySummary> monthly(String email, String recordKey, String from, String to) {
        try {
            if (!from.matches("[0-9]{4}-[0-9]{2}") || !to.matches("[0-9]{4}-[0-9]{2}")) {
                throw invalid("월간 날짜 형식은 yyyy-MM입니다.");
            }
            YearMonth start = YearMonth.parse(from);
            YearMonth end = YearMonth.parse(to);
            validateRange(start.atDay(1), end.atDay(1));
            if (ChronoUnit.MONTHS.between(start, end) >= 24) throw invalid("월간 조회는 최대 24개월입니다.");
            return summarize(email, recordKey, start.atDay(1), end.plusMonths(1).atDay(1), true);
        } catch (DateTimeException exception) {
            throw invalid("유효하지 않은 월입니다.");
        }
    }

    private List<ActivitySummary> summarize(String email, String recordKey, LocalDate from,
            LocalDate toExclusive, boolean monthly) {
        if (recordKey.isBlank() || recordKey.length() > 100) throw invalid("recordKey가 올바르지 않습니다.");
        var member = members.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."));
        if (!member.getRecordKey().equals(recordKey)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인의 recordkey만 조회할 수 있습니다.");
        }
        var cached = cache.get(recordKey, from, toExclusive, monthly);
        if (cached != null) return cached;
        var fromUtc = from.atStartOfDay(SEOUL).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        var toUtc = toExclusive.atStartOfDay(SEOUL).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        var buckets = new TreeMap<String, Totals>();
        // ponytail: 현재 규모에서는 기간 내 값만 메모리에 집계한다. 대량 데이터에서는 DB 집계로 전환한다.
        for (var value : activities.findByRecordKeyAndPeriodFromGreaterThanEqualAndPeriodFromLessThan(
                recordKey, fromUtc, toUtc)) {
            var date = value.getPeriodFrom().atOffset(ZoneOffset.UTC).atZoneSameInstant(SEOUL).toLocalDate();
            String period = monthly ? YearMonth.from(date).toString() : date.toString();
            buckets.merge(period, new Totals(value.getSteps(), value.getCalories(), value.getDistance()), Totals::add);
        }
        var rows = buckets.entrySet().stream().map(entry -> new ActivitySummary(entry.getKey(),
                entry.getValue().steps().setScale(0, RoundingMode.HALF_UP).toBigIntegerExact(),
                entry.getValue().calories(), entry.getValue().distance(), recordKey)).toList();
        cache.put(recordKey, from, toExclusive, monthly, rows);
        return rows;
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from.getYear() < 1001 || to.getYear() > 9998 || to.isBefore(from)) {
            throw invalid("조회 연도는 1001~9998이며 시작일이 종료일보다 늦을 수 없습니다.");
        }
    }

    private static ResponseStatusException invalid(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }

    private record Totals(BigDecimal steps, BigDecimal calories, BigDecimal distance) {
        Totals add(Totals other) {
            return new Totals(steps.add(other.steps), calories.add(other.calories), distance.add(other.distance));
        }
    }
}
