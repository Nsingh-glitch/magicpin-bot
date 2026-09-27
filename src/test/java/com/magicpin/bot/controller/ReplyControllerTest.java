package com.magicpin.bot.controller;

import com.magicpin.bot.repository.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class ReplyControllerTest {

    private InMemoryStore store;
    private ReplyController controller;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        controller = new ReplyController(store);
    }

    @Test
    void customerYesReturnsSendResponse() {
        Map<String, Object> response = reply("c1", "customer", "YES");

        assertSend(response);
        assertFalse(String.valueOf(response.get("body")).contains("Booking confirmed"));
        assertTrue(String.valueOf(response.get("body")).contains("what would you like me to confirm"));
    }

    @Test
    void merchantYesReturnsSendResponse() {
        Map<String, Object> response = reply("m1", "merchant", "yes please");

        assertSend(response);
        assertFalse(String.valueOf(response.get("body")).contains("Campaign is now live"));
        assertTrue(String.valueOf(response.get("body")).contains("which campaign or action"));
    }

    @Test
    void stopUnsubscribeAndNoMoreAreTerminal() {
        assertEnd(reply("stop-1", "merchant", "STOP"));
        assertEnd(reply("stop-2", "merchant", "unsubscribe"));
        assertEnd(reply("stop-3", "merchant", "no more"));
    }

    @Test
    void normalCustomerAndMerchantRepliesReturnSend() {
        assertSend(reply("normal-customer", "customer", "I need more information"));
        assertSend(reply("normal-merchant", "merchant", "Tell me about this"));
    }

    @Test
    void ambiguousMaybeDoesNotConfirm() {
        Map<String, Object> customer = reply("maybe-customer", "customer", "maybe");
        Map<String, Object> merchant = reply("maybe-merchant", "merchant", "maybe");

        assertSend(customer);
        assertSend(merchant);
        assertFalse(String.valueOf(customer.get("body")).contains("Booking confirmed"));
        assertFalse(String.valueOf(merchant.get("body")).contains("Campaign is now live"));
    }

    @Test
    void emptyWhitespaceAndNullMessagesReturnSafeSendResponses() {
        assertSend(reply("empty-1", "customer", ""));
        assertSend(reply("empty-2", "customer", "   "));
        assertSend(reply("empty-3", "customer", null));
    }

    @Test
    void nullRequestReturnsSafeSendResponse() {
        Map<String, Object> response = controller.reply(null);

        assertSend(response);
        assertTrue(String.valueOf(response.get("rationale")).contains("Missing reply request"));
    }

    @Test
    void missingOrInvalidRoleDoesNotBecomeMerchant() {
        Map<String, Object> missing = reply("invalid-1", null, "yes");
        Map<String, Object> invalid = reply("invalid-2", "admin", "yes");
        Map<String, Object> normalized = reply("invalid-3", " CUSTOMER ", " yes ");

        assertSend(missing);
        assertSend(invalid);
        assertFalse(String.valueOf(missing.get("body")).contains("Campaign is now live"));
        assertFalse(String.valueOf(invalid.get("body")).contains("Campaign is now live"));
        assertFalse(String.valueOf(normalized.get("body")).contains("Booking confirmed"));
    }

    @Test
    void missingOrBlankConversationIdReturnsSafeResponse() {
        assertSend(reply(null, "customer", "hello"));
        assertSend(reply("   ", "customer", "hello"));
    }

    @Test
    void yesUsesBoundariesAndAllowsIntentionalFollowUpText() {
        Map<String, Object> exact = reply("yes-1", "merchant", " YES ");
        Map<String, Object> followUp = reply("yes-2", "merchant", "yes, please");
        Map<String, Object> embedded = reply("yes-3", "merchant", "yesterday");

        assertTrue(String.valueOf(exact.get("body")).contains("which campaign or action"));
        assertTrue(String.valueOf(followUp.get("body")).contains("which campaign or action"));
        assertFalse(String.valueOf(embedded.get("body")).contains("which campaign or action"));
    }

    @Test
    void stopUsesBoundariesAndPreservesExplicitPhraseMatching() {
        assertEnd(reply("stop-4", "merchant", "please STOP"));
        assertEnd(reply("stop-5", "merchant", "opt out"));

        Map<String, Object> stopwatch = reply("stop-6", "merchant", "stopwatch");
        Map<String, Object> stopping = reply("stop-7", "merchant", "stopping by later");
        Map<String, Object> nonstop = reply("stop-8", "merchant", "nonstop service");

        assertFalse("end".equals(stopwatch.get("action")));
        assertFalse("end".equals(stopping.get("action")));
        assertFalse("end".equals(nonstop.get("action")));
    }

    @Test
    void ordinaryDuplicateDoesNotEscalateSolelyBecauseItRepeats() {
        String message = "I would like to understand the campaign details";
        Map<String, Object> first = reply("duplicate-1", "merchant", message);
        Map<String, Object> second = reply("duplicate-1", "merchant", message);

        assertSend(first);
        assertSend(second);
        assertEquals(first.get("body"), second.get("body"));
    }

    @Test
    void repeatedKnownAutoReplyStillProbesThenEnds() {
        String autoReply = "I am currently unavailable and will get back to you shortly";
        Map<String, Object> first = reply("auto-1", "merchant", autoReply);
        Map<String, Object> second = reply("auto-1", "merchant", autoReply);
        Map<String, Object> third = reply("auto-1", "merchant", "hello");

        assertSend(first);
        assertEnd(second);
        assertEnd(third);
    }

    @Test
    void stopCreatesTerminalStateAndCannotBeReopened() {
        assertEnd(reply("terminal-1", "merchant", "STOP"));

        Map<String, Object> afterStop = reply("terminal-1", "merchant", "YES");
        Map<String, Object> repeatedStop = reply("terminal-1", "merchant", "STOP");

        assertEnd(afterStop);
        assertEnd(repeatedStop);
        assertFalse(String.valueOf(afterStop).contains("Campaign is now live"));
    }

    @Test
    void endCreatesTerminalStateAndCannotBeReopened() {
        String autoReply = "I am currently unavailable";
        assertSend(reply("ended-1", "merchant", autoReply));
        assertEnd(reply("ended-1", "merchant", autoReply));

        Map<String, Object> afterEnd = reply("ended-1", "merchant", "yes");
        assertEnd(afterEnd);
        assertFalse(String.valueOf(afterEnd).contains("Campaign is now live"));
    }

    @Test
    void customerYesAcknowledgesOnlyStoredBookingState() {
        store.save("customer:booking-a", Map.of(
                "scope", "customer",
                "context_id", "booking-a",
                "payload", Map.of("booking", Map.of("status", "confirmed", "service", "cleaning"))
        ));

        Map<String, Object> response = replyWithContext("booking-conversation", "customer", "YES", "customer", "booking-a");

        assertSend(response);
        assertTrue(String.valueOf(response.get("body")).contains("cleaning"));
        assertTrue(String.valueOf(response.get("body")).contains("confirmed"));
        assertFalse(String.valueOf(response.get("body")).contains("Booking confirmed!"));
    }

    @Test
    void merchantYesAcknowledgesOnlyStoredCampaignState() {
        store.save("merchant:campaign-a", Map.of(
                "scope", "merchant",
                "context_id", "campaign-a",
                "payload", Map.of("campaign", Map.of("status", "active", "name", "Summer menu"))
        ));

        Map<String, Object> response = replyWithContext("campaign-conversation", "merchant", "YES", "merchant", "campaign-a");

        assertSend(response);
        assertTrue(String.valueOf(response.get("body")).contains("Summer menu"));
        assertTrue(String.valueOf(response.get("body")).contains("active"));
        assertFalse(String.valueOf(response.get("body")).contains("Campaign is now live"));
    }

    @Test
    void triggerSelectionWithoutActionExecutionDoesNotConfirmAction() {
        store.save("trigger:trg_perf", Map.of(
                "scope", "trigger",
                "context_id", "trg_perf",
                "payload", Map.of("kind", "perf_dip")
        ));

        Map<String, Object> response = replyWithContext("trigger-conversation", "merchant", "YES", "trigger", "trg_perf");

        assertSend(response);
        assertTrue(String.valueOf(response.get("body")).contains("which campaign or action"));
        assertFalse(String.valueOf(response.get("body")).contains("activated"));
    }

    @Test
    void actionedTriggerCanBeAcknowledgedWithoutInventingCampaignState() {
        store.save("trigger:trg_done", Map.of(
                "scope", "trigger",
                "context_id", "trg_done",
                "payload", Map.of("kind", "perf_dip")
        ));
        store.recordTriggerAction("trg_done");

        Map<String, Object> response = replyWithContext("trigger-actioned", "merchant", "YES", "trigger", "trg_done");

        assertSend(response);
        assertTrue(String.valueOf(response.get("body")).contains("already recorded as completed"));
        assertFalse(String.valueOf(response.get("body")).contains("Campaign is now live"));
    }

    @Test
    void contextIsolatedByExplicitScopeAndContextId() {
        store.save("customer:context-a", Map.of(
                "scope", "customer",
                "context_id", "context-a",
                "payload", Map.of("booking", Map.of("status", "confirmed", "service", "massage"))
        ));
        store.save("customer:context-b", Map.of(
                "scope", "customer",
                "context_id", "context-b",
                "payload", Map.of("booking", Map.of("status", "confirmed", "service", "dental cleaning"))
        ));

        Map<String, Object> responseA = replyWithContext("conversation-a", "customer", "YES", "customer", "context-a");
        Map<String, Object> responseB = replyWithContext("conversation-b", "customer", "YES", "customer", "context-b");

        assertTrue(String.valueOf(responseA.get("body")).contains("massage"));
        assertFalse(String.valueOf(responseA.get("body")).contains("dental cleaning"));
        assertTrue(String.valueOf(responseB.get("body")).contains("dental cleaning"));
        assertFalse(String.valueOf(responseB.get("body")).contains("massage"));
    }

    @Test
    void delimiterCharactersInScopeAndContextIdCannotCollide() {
        store.saveContext("customer:alt", "x", Map.of(
                "scope", "customer:alt",
                "context_id", "x",
                "payload", Map.of("booking", Map.of("status", "confirmed", "service", "Collision A"))
        ));
        store.saveContext("customer", "alt:x", Map.of(
                "scope", "customer",
                "context_id", "alt:x",
                "payload", Map.of("booking", Map.of("status", "confirmed", "service", "Collision B"))
        ));

        Map<String, Object> responseA = replyWithContext(
                "delimiter-a", "customer", "YES", "customer:alt", "x"
        );
        Map<String, Object> responseB = replyWithContext(
                "delimiter-b", "customer", "YES", "customer", "alt:x"
        );

        assertTrue(String.valueOf(responseA.get("body")).contains("Collision A"));
        assertFalse(String.valueOf(responseA.get("body")).contains("Collision B"));
        assertTrue(String.valueOf(responseB.get("body")).contains("Collision B"));
        assertFalse(String.valueOf(responseB.get("body")).contains("Collision A"));
    }

    @Test
    void missingContextUsesSafeRoleSpecificFallback() {
        Map<String, Object> customer = reply("missing-context-customer", "customer", "YES");
        Map<String, Object> merchant = reply("missing-context-merchant", "merchant", "YES");

        assertSend(customer);
        assertSend(merchant);
        assertFalse(String.valueOf(customer.get("body")).contains("Booking confirmed"));
        assertFalse(String.valueOf(merchant.get("body")).contains("Campaign is now live"));
    }

    @Test
    void tickDecisionProvidesContextContinuityWithoutConfirmingExecution() {
        saveMerchant("m_tick", "restaurants");
        TickController tickController = new TickController(store);

        tickController.tick(Map.of("available_triggers", List.of(
                Map.of(
                    "id", "perf_dip",
                    "trigger_id", "perf_dip",
                    "kind", "perf_dip",
                    "payload", Map.of("merchant_id", "m_tick", "drop_percent", 18)
                )
        )));

        Map<String, Object> response = replyWithContext("tick-reply", "merchant", "YES", "merchant", "m_tick");

        assertSend(response);
        assertTrue(String.valueOf(response.get("body")).contains("performance signal"));
        assertFalse(String.valueOf(response.get("body")).contains("activated"));
        assertFalse(String.valueOf(response.get("body")).contains("completed"));
    }

    @Test
    void tickDecisionLinkageIsIsolatedPerContext() {
        saveMerchant("m_a", "restaurants");
        saveMerchant("m_b", "pharmacies");
        TickController tickController = new TickController(store);

        tickController.tick(Map.of("available_triggers", List.of(
                Map.of("id", "perf_dip", "trigger_id", "perf_dip", "kind", "perf_dip",
                    "payload", Map.of("merchant_id", "m_a", "drop_percent", 12)),
                Map.of("id", "recall_due", "trigger_id", "recall_due", "kind", "recall_due",
                    "payload", Map.of("merchant_id", "m_b", "due_count", 4))
        )));

        Map<String, Object> responseA = replyWithContext("reply-a", "merchant", "YES", "merchant", "m_a");
        Map<String, Object> responseB = replyWithContext("reply-b", "merchant", "YES", "merchant", "m_b");

        assertTrue(String.valueOf(responseA.get("body")).contains("performance signal"));
        assertFalse(String.valueOf(responseA.get("body")).contains("recall signal"));
        assertTrue(String.valueOf(responseB.get("body")).contains("recall signal"));
        assertFalse(String.valueOf(responseB.get("body")).contains("performance signal"));
    }

    @Test
    void contextWithoutPriorTickKeepsSafeClarification() {
        saveMerchant("m_no_tick", "restaurants");

        Map<String, Object> response = replyWithContext("no-tick", "merchant", "YES", "merchant", "m_no_tick");

        assertSend(response);
        assertTrue(String.valueOf(response.get("body")).contains("which campaign or action"));
        assertFalse(String.valueOf(response.get("body")).contains("latest"));
    }

    @Test
    void tickDecisionUsesContextIdSeparatelyFromMerchantId() {
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

        TickController tickController = new TickController(store);
        tickController.tick(Map.of("available_triggers", List.of(
                Map.of(
                    "id", "perf_dip",
                    "trigger_id", "perf_dip",
                    "kind", "perf_dip",
                    "payload", Map.of("merchant_id", "m_001", "drop_percent", 18)
                )
        )));

        Map<String, Object> decision = store.getLatestDecision("merchant", "ctx_001");
        assertNotNull(decision);
        assertEquals("ctx_001", decision.get("context_id"));
        assertEquals("m_001", decision.get("merchant_id"));
        assertNull(store.getLatestDecision("merchant", "m_001"));

        Map<String, Object> response = replyWithContext("ctx-reply", "merchant", "YES", "merchant", "ctx_001");
        assertSend(response);
        assertTrue(String.valueOf(response.get("body")).contains("performance signal"));
    }

    @Test
    void tickDecisionsRemainIsolatedWhenContextAndMerchantIdsDiffer() {
        store.save("merchant:ctx_001", merchantContext("ctx_001", "m_001", "restaurants"));
        store.save("merchant:ctx_002", merchantContext("ctx_002", "m_002", "pharmacies"));

        TickController tickController = new TickController(store);
        tickController.tick(Map.of("available_triggers", List.of(
                Map.of("id", "perf_dip", "trigger_id", "perf_dip", "kind", "perf_dip",
                    "payload", Map.of("merchant_id", "m_001", "drop_percent", 12)),
                Map.of("id", "recall_due", "trigger_id", "recall_due", "kind", "recall_due",
                    "payload", Map.of("merchant_id", "m_002", "due_count", 4))
        )));

        Map<String, Object> decisionA = store.getLatestDecision("merchant", "ctx_001");
        Map<String, Object> decisionB = store.getLatestDecision("merchant", "ctx_002");
        assertNotNull(decisionA);
        assertNotNull(decisionB);
        assertEquals("m_001", decisionA.get("merchant_id"));
        assertEquals("m_002", decisionB.get("merchant_id"));
        assertEquals("perf_dip", decisionA.get("trigger_id"));
        assertEquals("recall_due", decisionB.get("trigger_id"));

        Map<String, Object> responseA = replyWithContext("ctx-reply-a", "merchant", "YES", "merchant", "ctx_001");
        Map<String, Object> responseB = replyWithContext("ctx-reply-b", "merchant", "YES", "merchant", "ctx_002");
        assertTrue(String.valueOf(responseA.get("body")).contains("performance signal"));
        assertFalse(String.valueOf(responseA.get("body")).contains("recall signal"));
        assertTrue(String.valueOf(responseB.get("body")).contains("recall signal"));
        assertFalse(String.valueOf(responseB.get("body")).contains("performance signal"));
    }

    @Test
    void responseShapesRemainCompatible() {
        Map<String, Object> send = reply("shape-send", "merchant", "hello");
        Map<String, Object> wait = reply("shape-wait", "merchant", "later");
        Map<String, Object> end = reply("shape-end", "merchant", "STOP");

        assertSend(send);
        assertEquals("wait", wait.get("action"));
        assertInstanceOf(Integer.class, wait.get("wait_seconds"));
        assertNotNull(wait.get("rationale"));
        assertEquals("end", end.get("action"));
        assertNotNull(end.get("rationale"));
    }

    @Test
    void concurrentRepliesKeepEachConversationStateConsistent() throws Exception {
        String autoReply = "I am currently unavailable";
        List<String> conversationIds = List.of("concurrent-a", "concurrent-b", "concurrent-c");
        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Map<String, Object>>> replies = new java.util.ArrayList<>();
            for (String conversationId : conversationIds) {
                for (int i = 0; i < 2; i++) {
                    replies.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return reply(conversationId, "merchant", autoReply);
                    }));
                }
            }

            ready.await();
            start.countDown();
            for (Future<Map<String, Object>> reply : replies) {
                assertTrue(Set.of("send", "end").contains(reply.get().get("action")));
            }
            for (String conversationId : conversationIds) {
                assertEnd(reply(conversationId, "merchant", "hello"));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private Map<String, Object> reply(String conversationId, String role, String message) {
        Map<String, Object> request = new HashMap<>();
        request.put("conversation_id", conversationId);
        request.put("from_role", role);
        request.put("message", message);
        return controller.reply(request);
    }

    private Map<String, Object> replyWithContext(String conversationId, String role, String message, String scope, String contextId) {
        Map<String, Object> request = new HashMap<>();
        request.put("conversation_id", conversationId);
        request.put("from_role", role);
        request.put("message", message);
        request.put("scope", scope);
        request.put("context_id", contextId);
        return controller.reply(request);
    }

    private void saveMerchant(String merchantId, String category) {
        store.save("merchant:" + merchantId, merchantContext(merchantId, merchantId, category));
    }

    private Map<String, Object> merchantContext(String contextId, String merchantId, String category) {
        return Map.of(
                "scope", "merchant",
                "context_id", contextId,
                "payload", Map.of(
                    "merchant_id", merchantId,
                    "category_slug", category,
                    "identity", Map.of("name", merchantId),
                    "signals", Map.of("trial_ending_soon", false),
                    "metrics", Map.of(),
                    "performance", Map.of()
                )
        );
    }

    private void assertSend(Map<String, Object> response) {
        assertEquals("send", response.get("action"));
        assertNotNull(response.get("body"));
        assertNotNull(response.get("cta"));
        assertNotNull(response.get("rationale"));
    }

    private void assertEnd(Map<String, Object> response) {
        assertEquals("end", response.get("action"));
        assertNotNull(response.get("rationale"));
    }
}
