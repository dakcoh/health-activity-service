package com.example.healthactivity.activity;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.math.BigDecimal;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityRepository extends JpaRepository<ActivityRecord, Long> {
    interface Values {
        LocalDateTime getPeriodFrom();
        BigDecimal getSteps();
        BigDecimal getCalories();
        BigDecimal getDistance();
    }

    List<Values> findByRecordKeyAndPeriodFromGreaterThanEqualAndPeriodFromLessThan(
            String recordKey, LocalDateTime from, LocalDateTime toExclusive);

    List<ActivityRecord> findByRecordKeyAndSourceNameAndPeriodFromIn(
            String recordKey, String sourceName, Collection<LocalDateTime> starts);
}
