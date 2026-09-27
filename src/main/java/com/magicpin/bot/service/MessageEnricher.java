package com.magicpin.bot.service;

import java.util.Map;
import java.util.Optional;

public interface MessageEnricher {

    Optional<String> enrich(String deterministicMessage, Map<String, Object> verifiedFacts);

    /**
     * Structured enrichment with explicit separation of facts, decision, and context.
     * Default delegates to the original method for backward compatibility.
     */
    default Optional<String> enrich(String deterministicMessage, GeminiGenerationRequest request) {
        return enrich(deterministicMessage, request.verifiedFacts());
    }
}
