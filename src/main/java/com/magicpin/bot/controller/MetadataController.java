package com.magicpin.bot.controller;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1")
public class MetadataController {

    @GetMapping("/metadata")
    public Map<String, Object> meta() {
        return Map.of(
                "name", "Sumit Bot",
                "version", "1.0",
                "model", "deterministic composer with optional gemini-3.1-flash-lite enrichment",
                "approach", "grounded trigger scoring with deterministic fallback"
        );
    }
}
