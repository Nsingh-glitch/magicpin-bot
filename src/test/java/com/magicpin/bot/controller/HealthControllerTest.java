package com.magicpin.bot.controller;

import com.magicpin.bot.repository.InMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthControllerTest {

    @Test
    void reportsLivenessAndNonSensitiveRuntimeMetadata() {
        InMemoryStore store = new InMemoryStore();
        store.saveContext("merchant", "m_1", Map.of("scope", "merchant", "context_id", "m_1"));

        Map<String, Object> response = new HealthController(store).health();

        assertEquals("ok", response.get("status"));
        assertEquals(1, response.get("contexts_loaded"));
        assertInstanceOf(Long.class, response.get("uptime_seconds"));
        assertTrue((Long) response.get("uptime_seconds") >= 0);
    }
}
