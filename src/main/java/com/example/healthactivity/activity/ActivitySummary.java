package com.example.healthactivity.activity;

import java.math.BigDecimal;
import java.math.BigInteger;

public record ActivitySummary(String period, BigInteger steps, BigDecimal calories,
        BigDecimal distance, String recordkey) {}
