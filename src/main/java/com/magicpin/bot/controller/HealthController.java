package com.magicpin.bot.controller;

import com.magicpin.bot.repository.InMemoryStore;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping({"", "/v1"})
public class HealthController {

    private final InMemoryStore store;
    private final Instant startedAt = Instant.now();

    public HealthController(InMemoryStore store) {
        this.store = store;
    }

    @GetMapping({"/healthz", "/health"})
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "uptime_seconds", java.time.Duration.between(startedAt, Instant.now()).toSeconds(),
                "contexts_loaded", store.getAll().size()
        );
    }
}
