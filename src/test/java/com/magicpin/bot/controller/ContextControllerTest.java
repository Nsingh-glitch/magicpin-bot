package com.magicpin.bot.controller;

import com.magicpin.bot.repository.InMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextControllerTest {

    @Test
    void rejectsMissingOrBlankContextIdentity() {
        ContextController controller = new ContextController(new InMemoryStore());

        assertBadRequest(() -> controller.save(Map.of()));
        assertBadRequest(() -> controller.save(Map.of("scope", "merchant")));
        assertBadRequest(() -> controller.save(Map.of("context_id", "m_1")));
        assertBadRequest(() -> controller.save(Map.of("scope", " ", "context_id", "m_1")));
    }

    @Test
    void acceptsValidIdentityWithOptionalPayload() {
        InMemoryStore store = new InMemoryStore();
        ContextController controller = new ContextController(store);

        assertEquals(true, controller.save(Map.of(
                "scope", "merchant",
                "context_id", "m_1"
        )).get("accepted"));
        assertNotNull(store.getContext("merchant", "m_1"));
    }

    private void assertBadRequest(Runnable request) {
        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                request::run
        );
        assertEquals(400, error.getStatusCode().value());
    }
}
