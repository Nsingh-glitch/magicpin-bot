package com.magicpin.bot.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structured input contract for Gemini message enrichment.
 *
 * Explicitly separates:
 *
 * <ul>
 *     <li>Verified facts — authoritative merchant/trigger data from the request</li>
 *     <li>Deterministic decision — the trigger selection and generated message from the engine</li>
 *     <li>Conversation context — available prior decision or conversation state</li>
 *     <li>Retrieved knowledge — approved knowledge sanitized for Gemini</li>
 * </ul>
 *
 * Gemini may only improve wording.
 * All facts, decisions, identifiers and execution state remain authoritative.
 */
public final class GeminiGenerationRequest {

    private final Map<String, Object> verifiedFacts;
    private final Map<String, Object> deterministicDecision;
    private final Map<String, Object> conversationContext;
    private final List<RetrievedKnowledgeRequest> retrievedKnowledge;

    private GeminiGenerationRequest(Builder builder) {
        this.verifiedFacts =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(builder.verifiedFacts)
                );

        this.deterministicDecision =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(builder.deterministicDecision)
                );

        this.conversationContext =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(builder.conversationContext)
                );

        this.retrievedKnowledge =
                List.copyOf(builder.retrievedKnowledge);
    }

    /**
     * Authoritative merchant/trigger metrics.
     * Only values that actually exist in the request.
     */
    public Map<String, Object> verifiedFacts() {
        return verifiedFacts;
    }

    /**
     * Authoritative deterministic engine output:
     * body, CTA, rationale and execution state.
     */
    public Map<String, Object> deterministicDecision() {
        return deterministicDecision;
    }

    /**
     * Available conversation context:
     * latest stored decision or prior message.
     */
    public Map<String, Object> conversationContext() {
        return conversationContext;
    }

    /**
     * Approved retrieved knowledge.
     *
     * This list is already sanitized and contains no internal identifiers.
     */
    public List<RetrievedKnowledgeRequest> retrievedKnowledge() {
        return retrievedKnowledge;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final Map<String, Object> verifiedFacts =
                new LinkedHashMap<>();

        private final Map<String, Object> deterministicDecision =
                new LinkedHashMap<>();

        private final Map<String, Object> conversationContext =
                new LinkedHashMap<>();

        private final List<RetrievedKnowledgeRequest> retrievedKnowledge =
                new java.util.ArrayList<>();

        private Builder() {
        }

        /*
         * These keys must never be exposed to Gemini as ordinary facts.
         */
        private static final Set<String> INTERNAL_FACT_KEYS = Set.of(
                "merchant_id",
                "trigger_id",
                "context_id",
                "trigger_kind",
                "selected_trigger_id",
                "selected_trigger_kind",
                "scope"
        );

        /*
         * Internal deterministic identifiers are application-side only.
         */
        private static final Set<String> INTERNAL_DECISION_KEYS = Set.of(
                "selected_trigger_id",
                "selected_trigger_kind",
                "merchant_id",
                "trigger_id",
                "context_id",
                "scope"
        );

        /*
         * Internal latest-decision fields are not needed by Gemini.
         */
        private static final Set<String> LATEST_DECISION_INTERNAL_KEYS = Set.of(
                "scope",
                "context_id",
                "merchant_id",
                "trigger_id",
                "trigger_kind",
                "actioned"
        );

        // ── Verified facts ──────────────────────────────────────────

        public Builder merchantId(String merchantId) {
            // Deliberately not exposed to Gemini.
            return this;
        }

        public Builder merchantName(String merchantName) {
            if (merchantName != null && !merchantName.isBlank()) {
                verifiedFacts.put("merchant_name", merchantName);
            }
            return this;
        }

        public Builder category(String category) {
            if (category != null && !category.isBlank()) {
                verifiedFacts.put("category", category);
            }
            return this;
        }

        public Builder triggerId(String triggerId) {
            // Deliberately not exposed to Gemini.
            return this;
        }

        public Builder triggerKind(String triggerKind) {
            // Deliberately not exposed to Gemini.
            return this;
        }

        public Builder dueCount(int dueCount) {
            if (dueCount > 0) {
                verifiedFacts.put("due_count", dueCount);
            }
            return this;
        }

        public Builder dropPercent(int dropPercent) {
            if (dropPercent > 0) {
                verifiedFacts.put("drop_percent", dropPercent);
            }
            return this;
        }

        public Builder daysLeft(int daysLeft) {
            if (daysLeft > 0) {
                verifiedFacts.put("days_left", daysLeft);
            }
            return this;
        }

        public Builder openSlots(int openSlots) {
            if (openSlots > 0) {
                verifiedFacts.put("open_slots", openSlots);
            }
            return this;
        }

        public Builder topItem(String topItem) {
            if (topItem != null && !topItem.isBlank()) {
                verifiedFacts.put("top_item", topItem);
            }
            return this;
        }

        public Builder digestTitle(String digestTitle) {
            if (digestTitle != null && !digestTitle.isBlank()) {
                verifiedFacts.put("digest_title", digestTitle);
            }
            return this;
        }

        public Builder urgency(int urgency) {
            if (urgency > 0) {
                verifiedFacts.put("urgency", urgency);
            }
            return this;
        }

        public Builder fact(String key, Object value) {
            if (key != null && !key.isBlank() && value != null) {
                String str = value.toString();

                if (!str.isBlank()
                        && !INTERNAL_FACT_KEYS.contains(key)) {
                    verifiedFacts.put(key, value);
                }
            }

            return this;
        }

        // ── Deterministic decision ──────────────────────────────────

        public Builder deterministicBody(String body) {
            if (body != null && !body.isBlank()) {
                deterministicDecision.put("body", body);
            }
            return this;
        }

        public Builder cta(String cta) {
            if (cta != null && !cta.isBlank()) {
                deterministicDecision.put("cta", cta);
            }
            return this;
        }

        public Builder rationale(String rationale) {
            if (rationale != null && !rationale.isBlank()) {
                deterministicDecision.put(
                        "rationale_summary",
                        rationale
                );
            }
            return this;
        }

        public Builder executionOccurred(boolean occurred) {
            deterministicDecision.put(
                    "execution_occurred",
                    occurred
            );
            return this;
        }

        public Builder selectedTriggerId(String triggerId) {
            // Deliberately not exposed to Gemini.
            return this;
        }

        public Builder selectedTriggerKind(String triggerKind) {
            // Deliberately not exposed to Gemini.
            return this;
        }

        public Builder decisionFact(String key, Object value) {
            if (key != null && !key.isBlank() && value != null) {
                String str = value.toString();

                if (!str.isBlank()
                        && !INTERNAL_DECISION_KEYS.contains(key)) {
                    deterministicDecision.put(key, value);
                }
            }

            return this;
        }

        // ── Conversation context ────────────────────────────────────

        public Builder latestDecision(
                Map<String, Object> decision
        ) {
            if (decision == null || decision.isEmpty()) {
                return this;
            }

            Map<String, Object> sanitized =
                    new LinkedHashMap<>();

            for (Map.Entry<String, Object> entry
                    : decision.entrySet()) {

                String key = entry.getKey();
                Object value = entry.getValue();

                if (key == null
                        || LATEST_DECISION_INTERNAL_KEYS.contains(key)) {
                    continue;
                }

                if (value == null) {
                    continue;
                }

                String str = value.toString();

                if (!str.isBlank()) {
                    sanitized.put(key, value);
                }
            }

            if (!sanitized.isEmpty()) {
                conversationContext.put(
                        "latest_decision",
                        sanitized
                );
            }

            return this;
        }

        public Builder priorMessage(String message) {
            if (message != null && !message.isBlank()) {
                conversationContext.put(
                        "prior_message",
                        message
                );
            }

            return this;
        }

        // ── Retrieved knowledge ─────────────────────────────────────

        public Builder retrievedKnowledge(
                List<RetrievedKnowledgeRequest> knowledge
        ) {
            if (knowledge == null || knowledge.isEmpty()) {
                return this;
            }

            for (RetrievedKnowledgeRequest item : knowledge) {
                if (item == null) {
                    continue;
                }

                /*
                 * The DTO itself contains no internal IDs.
                 * Still validate that the useful content is present.
                 */
                if ((item.title() == null || item.title().isBlank())
                        && (item.content() == null || item.content().isBlank())) {
                    continue;
                }

                retrievedKnowledge.add(item);
            }

            return this;
        }

        public GeminiGenerationRequest build() {
            return new GeminiGenerationRequest(this);
        }
    }
}