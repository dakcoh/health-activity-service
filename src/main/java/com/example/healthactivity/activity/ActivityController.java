package com.example.healthactivity.activity;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/activities")
public class ActivityController {
    private final ActivityService activities;
    private final ActivityQueryService queries;

    public ActivityController(ActivityService activities, ActivityQueryService queries) {
        this.activities = activities;
        this.queries = queries;
    }

    @GetMapping("/daily")
    public List<ActivitySummary> daily(Principal principal, @RequestParam String recordKey,
            @RequestParam String from, @RequestParam String to) {
        return queries.daily(principal.getName(), recordKey, from, to);
    }

    @GetMapping("/monthly")
    public List<ActivitySummary> monthly(Principal principal, @RequestParam String recordKey,
            @RequestParam String from, @RequestParam String to) {
        return queries.monthly(principal.getName(), recordKey, from, to);
    }

    @PostMapping
    public ActivityService.Receipt store(Principal principal, @Valid @RequestBody ActivityRequest request) {
        return activities.store(principal.getName(), request);
    }
}
