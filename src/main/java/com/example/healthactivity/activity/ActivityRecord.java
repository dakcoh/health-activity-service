package com.example.healthactivity.activity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "activity_records")
public class ActivityRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String recordKey;
    @Column(nullable = false, length = 30)
    private String sourceName;
    @Column(nullable = false)
    private int sourceMode;
    @Column(nullable = false, length = 100)
    private String sourceType;
    @Column(nullable = false, length = 100)
    private String productName;
    @Column(nullable = false, length = 100)
    private String productVendor;
    @Column(nullable = false)
    private LocalDateTime periodFrom;
    @Column(nullable = false)
    private LocalDateTime periodTo;
    @Column(nullable = false, precision = 38, scale = 20)
    private BigDecimal steps;
    @Column(nullable = false, precision = 38, scale = 20)
    private BigDecimal calories;
    @Column(nullable = false, precision = 38, scale = 20)
    private BigDecimal distance;
    @Column(nullable = false)
    private LocalDateTime lastUpdate;
    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected ActivityRecord() {}

    ActivityRecord(String recordKey, ActivityRequest.Source source, LocalDateTime from, LocalDateTime to,
            ActivityRequest.Entry entry, LocalDateTime lastUpdate) {
        this.recordKey = recordKey;
        this.sourceName = source.name();
        this.sourceMode = source.mode();
        this.sourceType = source.type();
        this.productName = source.product().name();
        this.productVendor = source.product().vender();
        this.periodFrom = from;
        this.periodTo = to;
        this.steps = entry.steps();
        this.calories = entry.calories().value();
        this.distance = entry.distance().value();
        this.lastUpdate = lastUpdate;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    record Interval(LocalDateTime from, LocalDateTime to) {}
    Interval interval() { return new Interval(periodFrom, periodTo); }

    boolean hasSameValues(ActivityRecord other) {
        return steps.compareTo(other.steps) == 0 && calories.compareTo(other.calories) == 0
                && distance.compareTo(other.distance) == 0;
    }

    public LocalDateTime getPeriodFrom() { return periodFrom; }
    public LocalDateTime getPeriodTo() { return periodTo; }
    public BigDecimal getSteps() { return steps; }
    public BigDecimal getCalories() { return calories; }
    public BigDecimal getDistance() { return distance; }
}
