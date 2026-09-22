package com.example.healthactivity.activity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public record ActivityRequest(
        @NotBlank @Size(max = 100) String recordkey,
        @NotNull @Valid Data data,
        @NotNull @Pattern(regexp = "steps") String type,
        @NotBlank @Size(max = 40) String lastUpdate) {

    public record Data(
            @NotEmpty @Size(max = 5000) List<@NotNull @Valid Entry> entries,
            @NotNull @Valid Source source) {}

    public record Source(
            @NotNull @Pattern(regexp = "SamsungHealth|Health Kit") String name,
            @NotNull Integer mode,
            @NotNull @Size(max = 100) String type,
            @NotNull @Valid Product product) {}

    // 원본 API의 'vender' 철자를 입력 경계에서 유지한다.
    public record Product(@NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 100) String vender) {}

    public record Entry(
            @NotNull @Valid Period period,
            @NotNull @DecimalMin("0") @Digits(integer = 18, fraction = 20) BigDecimal steps,
            @NotNull @Valid Metric distance,
            @NotNull @Valid Metric calories) {}

    public record Period(@NotBlank @Size(max = 40) String from,
            @NotBlank @Size(max = 40) String to) {}

    public record Metric(@NotNull @DecimalMin("0") @Digits(integer = 18, fraction = 20) BigDecimal value,
            @NotBlank @Size(max = 10) String unit) {}
}
