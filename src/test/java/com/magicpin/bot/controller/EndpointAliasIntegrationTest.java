package com.magicpin.bot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EndpointAliasIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void healthAliasesMatchVersionedHealth() throws Exception {
        JsonNode versioned = getJson("/v1/healthz");
        JsonNode healthz = getJson("/healthz");
        JsonNode health = getJson("/health");

        assertEquals("ok", versioned.path("status").asText());
        assertTrue(versioned.path("uptime_seconds").isIntegralNumber());
        assertEquals(versioned.path("status"), healthz.path("status"));
        assertEquals(versioned.path("contexts_loaded"), healthz.path("contexts_loaded"));
        assertEquals(versioned.path("status"), health.path("status"));
        assertEquals(versioned.path("contexts_loaded"), health.path("contexts_loaded"));
    }

    @Test
    void contextAliasMatchesVersionedContext() throws Exception {
        String versionedRequest = """
                {"scope":"merchant","context_id":"alias-context-v1","payload":{}}
                """;
        String aliasRequest = """
                {"scope":"merchant","context_id":"alias-context-root","payload":{}}
                """;

        assertEquals(postJson("/v1/context", versionedRequest), postJson("/context", aliasRequest));
    }

    @Test
    void tickAliasMatchesVersionedTick() throws Exception {
        String merchantContext = """
                {
                  "scope":"merchant",
                  "context_id":"alias-tick-merchant",
                  "payload":{
                    "merchant_id":"alias-tick-merchant",
                    "category_slug":"restaurants",
                    "identity":{"name":"Alias Bistro"},
                    "signals":{},
                    "metrics":{},
                    "performance":{}
                  }
                }
                """;
        postJson("/v1/context", merchantContext);

        String tickRequest = """
                {
                  "available_triggers":[{
                    "trigger_id":"alias-tick-perf",
                    "id":"alias-tick-perf",
                    "kind":"perf_dip",
                    "payload":{"merchant_id":"alias-tick-merchant","drop_percent":18}
                  }]
                }
                """;

        JsonNode versioned = postJson("/v1/tick", tickRequest);
        JsonNode alias = postJson("/tick", tickRequest);

        assertEquals(versioned, alias);
        assertEquals("alias-tick-perf", alias.path("actions").get(0).path("trigger_id").asText());
    }

    @Test
    void replyAliasMatchesVersionedReply() throws Exception {
        String versionedRequest = """
                {"conversation_id":"alias-reply-v1","from_role":"merchant","message":"hello"}
                """;
        String aliasRequest = """
                {"conversation_id":"alias-reply-root","from_role":"merchant","message":"hello"}
                """;

        assertEquals(postJson("/v1/reply", versionedRequest), postJson("/reply", aliasRequest));
    }

    private JsonNode getJson(String path) throws Exception {
        String response = mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode postJson(String path, String request) throws Exception {
        String response = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }
}
