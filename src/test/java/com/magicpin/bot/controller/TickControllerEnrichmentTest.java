package com.magicpin.bot.controller;

import com.magicpin.bot.repository.InMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TickControllerEnrichmentTest {

    @Test
    void enrichmentCanReplaceOnlyBodyAndSelectionMetadataStaysDeterministic() {
        InMemoryStore store = new InMemoryStore();
        store.save("merchant:ctx_001", Map.of(
                "scope", "merchant",
                "context_id", "ctx_001",
                "payload", Map.of(
                    "merchant_id", "m_001",
                    "category_slug", "restaurants",
                    "identity", Map.of("name", "Saffron Table"),
                    "signals", Map.of("trial_ending_soon", false),
                    "metrics", Map.of(),
                    "performance", Map.of()
                )
        ));

        TickController controller = new TickController(store, (message, facts) -> Optional.of("Enriched customer message."));
        Map<String, Object> response = controller.tick(Map.of("available_triggers", List.of(
                Map.of(
                    "id", "perf_dip",
                    "trigger_id", "perf_dip",
                    "kind", "perf_dip",
                    "payload", Map.of("merchant_id", "m_001", "drop_percent", 18)
                )
        )));

        Map<?, ?> action = (Map<?, ?>) ((List<?>) response.get("actions")).get(0);
        assertEquals("Enriched customer message.", action.get("body"));
        assertEquals("send_message", action.get("type"));
        assertEquals("perf_dip", action.get("trigger_id"));
        assertEquals("m_001", action.get("merchant_id"));
        assertEquals("Reply YES", action.get("cta"));
        assertTrue(String.valueOf(action.get("rationale")).contains("performance"));
        assertEquals("perf_dip", store.getLatestDecision("merchant", "ctx_001").get("trigger_id"));
    }

    @Test
    void emptyEnrichmentFallsBackToDeterministicBody() {
        InMemoryStore store = new InMemoryStore();
        store.save("merchant:m_001", Map.of(
                "scope", "merchant",
                "context_id", "m_001",
                "payload", Map.of(
                    "merchant_id", "m_001",
                    "category_slug", "restaurants",
                    "identity", Map.of("name", "Saffron Table"),
                    "signals", Map.of("trial_ending_soon", false),
                    "metrics", Map.of(),
                    "performance", Map.of()
                )
        ));

        TickController controller = new TickController(store, (message, facts) -> Optional.empty());
        Map<String, Object> response = controller.tick(Map.of("available_triggers", List.of(
                Map.of("id", "perf_dip", "trigger_id", "perf_dip", "kind", "perf_dip",
                    "payload", Map.of("merchant_id", "m_001", "drop_percent", 18))
        )));

        Map<?, ?> action = (Map<?, ?>) ((List<?>) response.get("actions")).get(0);
        assertTrue(String.valueOf(action.get("body")).contains("performance is down 18%"));
        assertEquals("perf_dip", action.get("trigger_id"));
        assertEquals("Reply YES", action.get("cta"));
    }
}
