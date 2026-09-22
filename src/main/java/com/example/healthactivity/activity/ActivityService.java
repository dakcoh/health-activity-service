package com.example.healthactivity.activity;

import com.example.healthactivity.member.MemberRepository;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ActivityService {
    private static final DateTimeFormatter LOCAL = format("uuuu-MM-dd HH:mm:ss");
    private static final DateTimeFormatter OFFSET = format("uuuu-MM-dd'T'HH:mm:ssXX");
    private static final DateTimeFormatter UPDATED = format("uuuu-MM-dd HH:mm:ss XX");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final ActivityRepository activities;
    private final MemberRepository members;
    private final ActivitySummaryCache cache;

    public ActivityService(ActivityRepository activities, MemberRepository members, ActivitySummaryCache cache) {
        this.activities = activities;
        this.members = members;
        this.cache = cache;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt store(String email, ActivityRequest request) {
        var source = request.data().source();
        boolean samsung = source.name().equals("SamsungHealth");
        if (source.mode() != (samsung ? 9 : 10)) throw invalid("source.name과 mode가 일치하지 않습니다.");
        var updated = parse(request.lastUpdate(), UPDATED, false);
        var incoming = new ArrayList<ActivityRecord>();
        for (var entry : request.data().entries()) {
            if (!entry.distance().unit().equals("km") || !entry.calories().unit().equals("kcal")) {
                throw invalid("거리 단위는 km, 칼로리 단위는 kcal이어야 합니다.");
            }
            var from = parse(entry.period().from(), samsung ? LOCAL : OFFSET, samsung);
            var to = parse(entry.period().to(), samsung ? LOCAL : OFFSET, samsung);
            if (to.isBefore(from)) throw invalid("종료 시각은 시작 시각보다 이전일 수 없습니다.");
            incoming.add(new ActivityRecord(request.recordkey(), source, from, to, entry, updated));
        }

        // ponytail: 회원 단위 직렬화. 같은 회원의 대량 동시 수집이 필요해지면 출처·기간 단위 잠금으로 세분화한다.
        var member = members.findByEmailForUpdate(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."));
        if (!member.getRecordKey().equals(request.recordkey())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인의 recordkey만 사용할 수 있습니다.");
        }
        var known = new HashMap<ActivityRecord.Interval, ActivityRecord>();
        activities.findByRecordKeyAndSourceNameAndPeriodFromIn(request.recordkey(), source.name(),
                incoming.stream().map(ActivityRecord::getPeriodFrom).distinct().toList())
                .forEach(record -> known.put(record.interval(), record));
        var added = new ArrayList<ActivityRecord>();
        for (var candidate : incoming) {
            var existing = known.putIfAbsent(candidate.interval(), candidate);
            if (existing == null) added.add(candidate);
            else if (!existing.hasSameValues(candidate)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 기간에 다른 활동 값이 존재합니다.");
            }
        }
        // 검증과 충돌 확인을 끝낸 후 저장하여 요청 전체를 원자적으로 처리한다.
        activities.saveAllAndFlush(added);
        if (!added.isEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.evict(request.recordkey());
                }
            });
        }
        return new Receipt(request.recordkey(), incoming.size(), added.size(), incoming.size() - added.size());
    }

    private static DateTimeFormatter format(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    }

    private static LocalDateTime parse(String value, DateTimeFormatter format, boolean local) {
        try {
            var utc = local ? LocalDateTime.parse(value, format).atZone(SEOUL).withZoneSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime() : OffsetDateTime.parse(value, format).withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();
            if (utc.getYear() < 1000 || utc.getYear() > 9999) throw invalid("지원하지 않는 날짜 범위입니다.");
            return utc;
        } catch (DateTimeException exception) {
            throw invalid("날짜 형식 또는 날짜 값이 올바르지 않습니다.");
        }
    }

    private static ResponseStatusException invalid(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }

    public record Receipt(String recordkey, int received, int inserted, int duplicates) {}
}
