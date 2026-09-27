package com.magicpin.bot.controller;

import com.magicpin.bot.repository.InMemoryStore;
import com.magicpin.bot.service.GeminiGenerationRequest;
import com.magicpin.bot.service.KnowledgeItem;
import com.magicpin.bot.service.KnowledgeRetriever;
import com.magicpin.bot.service.MessageEnricher;
import com.magicpin.bot.service.RetrievedKnowledgeRequest;
import com.magicpin.bot.service.InMemoryKnowledgeRetriever;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping({"", "/v1"})
public class TickController {

    private final InMemoryStore store;
    private final MessageEnricher messageEnricher;
    private final KnowledgeRetriever knowledgeRetriever;

    /*
     * Backward-compatible constructor.
     *
     * Existing tests that instantiate TickController(store) continue to work.
     */
    public TickController(InMemoryStore store) {
        this(
            store,
            (deterministicMessage, verifiedFacts) -> Optional.empty(),
            (query, context, limit) -> List.of()
        );
    }

    public TickController(
            InMemoryStore store,
            MessageEnricher enricher
    ) {
        this(store, enricher, new InMemoryKnowledgeRetriever());
    }

    @Autowired
    public TickController(
            InMemoryStore store,
            MessageEnricher messageEnricher,
            KnowledgeRetriever knowledgeRetriever
    ) {
        this.store = store;
        this.messageEnricher = messageEnricher;
        this.knowledgeRetriever = knowledgeRetriever;
    }

    @PostMapping("/tick")
    public Map<String, Object> tick(
            @RequestBody(required = false) Map<String, Object> req
    ) {

        if (req == null) {
            req = new HashMap<>();
        }

        List<Map<String, Object>> actions = new ArrayList<>();
        List<Map<String, Object>> triggers = new ArrayList<>();

        Object rawTriggers = req.get("available_triggers");
        if (!(rawTriggers instanceof List<?>)) {
            rawTriggers = req.get("active_trigger_ids");
        }

        if (rawTriggers instanceof List<?>) {

            for (Object item : (List<?>) rawTriggers) {

                if (item instanceof Map) {

                    triggers.add(cast(item));

                } else if (item instanceof String triggerId) {

                    Map<String, Object> stored =
                            store.getContext("trigger", triggerId);

                    if (stored != null) {

                        triggers.add(stored);

                    } else {

                        Map<String, Object> shell = new HashMap<>();

                        shell.put("trigger_id", triggerId);
                        shell.put("id", triggerId);
                        shell.put("kind", inferKindFromId(triggerId));
                        shell.put("payload", new HashMap<>());

                        triggers.add(shell);
                    }
                }
            }
        }

        /*
         * Deterministic fallback trigger.
         *
         * This is only used when no trigger input was supplied.
         */
        if (triggers.isEmpty()) {

            Map<String, Object> fake = new HashMap<>();

            fake.put("trigger_id", "perf_dip");
            fake.put("id", "perf_dip");
            fake.put("kind", "perf_dip");
            fake.put(
                    "payload",
                    Map.of(
                            "due_count", 5,
                            "drop_percent", 20,
                            "days_left", 3
                    )
            );

            triggers.add(fake);
        }

        Collection<Map<String, Object>> allContexts = store.getAll();

        /*
         * No merchant context.
         */
        if (allContexts == null || allContexts.isEmpty()) {

            actions.add(
                    Map.of(
                            "type", "send_message",
                            "trigger_id", "perf_dip",
                            "merchant_id", "default",
                            "body",
                            "Your store has a recent demand dip. I can help you act on it. Reply YES.",
                            "cta", "Reply YES",
                            "send_as", "vera",
                            "suppression_key", "trigger:perf_dip:merchant:default",
                            "rationale",
                            "No merchant context was available; fallback trigger selected using the default demand-dip path."
                    )
            );

            return Map.of("actions", actions);
        }

        /*
         * Index category contexts so merchant contexts can use category
         * digest and peer information.
         */
        Map<String, Map<String, Object>> categoryContexts =
                new HashMap<>();

        for (Map<String, Object> ctx : allContexts) {

            if ("category".equals(ctx.get("scope"))) {

                String catId = stringValue(ctx.get("context_id"));

                if (catId != null) {
                    categoryContexts.put(catId, ctx);
                }
            }
        }

        /*
         * Process merchant contexts.
         */
        for (Map<String, Object> ctx : allContexts) {

            if (!"merchant".equals(ctx.get("scope"))) {
                continue;
            }

            Map<String, Object> payload =
                    cast(ctx.get("payload"));

            if (payload == null) {
                continue;
            }

            String scope =
                    stringValue(ctx.get("scope"));

            String contextId =
                    stringValue(ctx.get("context_id"));

            if (scope == null || scope.isBlank()) {
                scope = "merchant";
            }

            if (contextId == null || contextId.isBlank()) {
                contextId = "unknown_merchant";
            }

            String merchantId =
                    stringValue(payload.get("merchant_id"));

            if (merchantId == null || merchantId.isBlank()) {
                merchantId = contextId;
            }

            Map<String, Object> identity =
                    cast(payload.get("identity"));

            String merchantName =
                    identity != null && identity.get("name") != null
                            ? String.valueOf(identity.get("name"))
                            : "your store";

            String ownerName =
                    identity != null
                            ? stringValue(identity.get("owner_name"))
                            : null;

            String category =
                    payload.get("category_slug") != null
                            ? String.valueOf(payload.get("category_slug"))
                            : null;

            Map<String, Object> signals =
                    cast(payload.get("signals"));

            boolean isNewMerchant =
                    signals != null
                            && Boolean.TRUE.equals(
                            signals.get("is_new_merchant")
                    );

            boolean trialEndingSoon =
                    signals != null
                            && Boolean.TRUE.equals(
                            signals.get("trial_ending_soon")
                    );

            boolean iplEligible =
                    signals != null
                            && Boolean.TRUE.equals(
                            signals.get("ipl_eligible_locality")
                    );

            String perfStatus =
                    signals != null
                            && signals.get("perf_status") != null
                            ? String.valueOf(
                            signals.get("perf_status")
                    )
                            : "";

            Map<String, Object> metrics =
                    cast(payload.get("metrics"));

            Map<String, Object> performance =
                    cast(payload.get("performance"));

            int openSlots = 0;

            if (metrics != null) {
                openSlots =
                        getInt(metrics, "open_slots", openSlots);
            }

            if (openSlots == 0 && performance != null) {
                openSlots =
                        getInt(
                                performance,
                                "open_slots",
                                openSlots
                        );
            }

            String digestTitle = null;
            String digestSource = null;
            String catPeerCtr = null;

            Map<String, Object> catCtx =
                    categoryContexts.get(category);

            if (catCtx != null) {

                Map<String, Object> catPayload =
                        cast(catCtx.get("payload"));

                if (catPayload != null) {

                    Object rawDigest =
                            catPayload.get("digest");

                    if (rawDigest instanceof List<?> digest
                            && !digest.isEmpty()) {

                        Map<String, Object> topDigest =
                                cast(digest.get(0));

                        if (topDigest != null) {

                            digestTitle =
                                    stringValue(
                                            topDigest.get("title")
                                    );

                            digestSource =
                                    stringValue(
                                            topDigest.get("source")
                                    );
                        }
                    }

                    Map<String, Object> peerStats =
                            cast(catPayload.get("peer_stats"));

                    if (peerStats != null
                            && peerStats.get("avg_ctr") != null) {

                        catPeerCtr =
                                peerStats
                                        .get("avg_ctr")
                                        .toString();
                    }
                }
            }

            /*
             * Deterministic trigger selection.
             */
            TriggerCandidate selected =
                    selectBestTrigger(
                            triggers,
                            merchantId,
                            category,
                            payload
                    );

            /*
             * A tick can contain triggers for several merchants. When none
             * of them applies to this merchant, do not manufacture an
             * action from another merchant's signal.
             */
            if (selected == null) {
                continue;
            }

            Map<String, Object> trigger =
                    selected.trigger;

            String triggerId =
                    selected.triggerId;

            String triggerKind =
                    selected.triggerKind;

            Map<String, Object> tPayload =
                    selected.payload;

            int due =
                    getInt(
                            tPayload,
                            "due_count",
                            0
                    );

            int drop =
                    getInt(
                            tPayload,
                            "drop_percent",
                            0
                    );

            int days =
                    getInt(
                            tPayload,
                            "days_left",
                            0
                    );

            String topItemId = null;

            if (tPayload != null) {

                topItemId =
                        stringValue(
                                tPayload.get("top_item_id")
                        );

                if (topItemId == null) {

                    Map<String, Object> topItem =
                            cast(tPayload.get("top_item"));

                    if (topItem != null) {

                        topItemId =
                                stringValue(
                                        topItem.get("id")
                                );
                    }
                }
            }

            if (digestTitle == null && tPayload != null) {

                Map<String, Object> topItem =
                        cast(tPayload.get("top_item"));

                if (topItem != null) {

                    digestTitle =
                            stringValue(
                                    topItem.get("title")
                            );

                    digestSource =
                            stringValue(
                                    topItem.get("source")
                            );
                }
            }

            /*
             * Deterministic message remains the source of truth.
             */
            String message =
                    generateMessage(
                            triggerKind,
                            merchantName,
                            ownerName,
                            category,
                            due,
                            drop,
                            days,
                            topItemId,
                            isNewMerchant,
                            trialEndingSoon,
                            iplEligible,
                            perfStatus,
                            openSlots,
                            catPeerCtr,
                            digestTitle,
                            digestSource
                    );

            /*
             * Verified facts passed into the structured Gemini contract.
             */
            Map<String, Object> verifiedFacts =
                    new LinkedHashMap<>();

            verifiedFacts.put(
                    "merchant_name",
                    merchantName
            );

            if (category != null && !category.isBlank()) {
                verifiedFacts.put(
                        "category",
                        category
                );
            }

            if (due > 0) {
                verifiedFacts.put(
                        "due_count",
                        due
                );
            }

            if (drop > 0) {
                verifiedFacts.put(
                        "drop_percent",
                        drop
                );
            }

            if (days > 0) {
                verifiedFacts.put(
                        "days_left",
                        days
                );
            }

            if (topItemId != null
                    && !topItemId.isBlank()) {

                verifiedFacts.put(
                        "top_item",
                        topItemId
                );
            }

            if (openSlots > 0) {
                verifiedFacts.put(
                        "open_slots",
                        openSlots
                );
            }

            if (digestTitle != null
                    && !digestTitle.isBlank()) {

                verifiedFacts.put(
                        "digest_title",
                        digestTitle
                );
            }

            String rationale =
                    buildRationale(
                            triggerKind,
                            category,
                            merchantId,
                            due,
                            drop,
                            days,
                            tPayload
                    );

            /*
             * ---------------------------------------------------------
             * RAG / KNOWLEDGE RETRIEVAL
             * ---------------------------------------------------------
             *
             * Internal IDs are used only inside the retrieval context.
             * They are NOT passed to Gemini through RetrievedKnowledgeRequest.
             */
            Map<String, Object> retrievalContext =
                    new LinkedHashMap<>();

            retrievalContext.put(
                    "scope",
                    scope
            );

            retrievalContext.put(
                    "context_id",
                    contextId
            );

            retrievalContext.put(
                    "merchant_id",
                    merchantId
            );

            if (category != null
                    && !category.isBlank()) {

                retrievalContext.put(
                        "category",
                        category
                );
            }

            retrievalContext.put(
                    "trigger_kind",
                    triggerKind
            );

            retrievalContext.put(
                    "trigger_id",
                    triggerId
            );

            /*
             * The deterministic message is used as the retrieval query.
             * The retriever decides which approved knowledge is relevant.
             */
            List<KnowledgeItem> retrievedItems =
                    knowledgeRetriever.retrieve(
                            message,
                            retrievalContext,
                            3
                    );

            List<RetrievedKnowledgeRequest> retrievedKnowledge =
                    retrievedItems == null
                            ? List.of()
                            : retrievedItems.stream()
                            .filter(Objects::nonNull)
                            .filter(KnowledgeItem::approved)
                            .map(
                                    item ->
                                            new RetrievedKnowledgeRequest(
                                                    item.title(),
                                                    item.content(),
                                                    item.source(),
                                                    item.category()
                                            )
                            )
                            .toList();

            /*
             * Latest deterministic decision from this merchant/context.
             */
            Map<String, Object> latestDecision =
                    store.getLatestDecision(
                            scope,
                            contextId
                    );

            /*
             * Structured Gemini request.
             *
             * Gemini receives:
             * - verified facts
             * - deterministic decision
             * - conversation context
             * - approved retrieved knowledge
             */
            GeminiGenerationRequest generationRequest =
                    GeminiGenerationRequest
                            .builder()

                            .merchantId(merchantId)

                            .merchantName(merchantName)

                            .category(category)

                            .triggerId(triggerId)

                            .triggerKind(triggerKind)

                            .dueCount(due)

                            .dropPercent(drop)

                            .daysLeft(days)

                            .openSlots(openSlots)

                            .topItem(topItemId)

                            .digestTitle(digestTitle)

                            .deterministicBody(message)

                            .cta("Reply YES")

                            .rationale(rationale)

                            .executionOccurred(false)

                            .selectedTriggerId(triggerId)

                            .selectedTriggerKind(triggerKind)

                            .latestDecision(latestDecision)

                            .retrievedKnowledge(
                                    retrievedKnowledge
                            )

                            .build();

            /*
             * Gemini may rewrite ONLY the body.
             *
             * If Gemini is disabled, unavailable, invalid, or rejected,
             * MessageEnricher falls back to the deterministic message.
             */
            message =
                    messageEnricher
                            .enrich(
                                    message,
                                    generationRequest
                            )
                            .orElse(message);

            /*
             * Selection is recorded separately from execution.
             */
            store.recordTriggerSelection(
                    triggerId
            );

            Map<String, Object> action =
                    new LinkedHashMap<>();

            action.put(
                    "type",
                    "send_message"
            );

            action.put(
                    "trigger_id",
                    triggerId
            );

            action.put(
                    "merchant_id",
                    merchantId
            );

            action.put(
                    "body",
                    message
            );

            action.put(
                    "cta",
                    "Reply YES"
            );

            /*
             * The challenge composition contract distinguishes who sends
             * the message and how the scheduler deduplicates it. Tick
             * currently composes merchant-facing messages only, so Vera is
             * always the sender. Keep the existing fields for API
             * compatibility and add the contract fields alongside them.
             */
            action.put(
                    "send_as",
                    "vera"
            );

            action.put(
                    "suppression_key",
                    suppressionKey(
                            trigger,
                            tPayload,
                            triggerId,
                            merchantId
                    )
            );

            action.put(
                    "rationale",
                    buildRationale(
                            triggerKind,
                            category,
                            merchantId,
                            due,
                            drop,
                            days,
                            tPayload
                    )
            );

            actions.add(action);

            /*
             * Persist latest decision using:
             *
             * scope + context_id
             *
             * NOT merchant_id.
             */
            store.recordLatestDecision(
                    scope,
                    contextId,
                    triggerId,
                    triggerKind,
                    merchantId,
                    message
            );
        }

        return Map.of(
                "actions",
                actions
        );
    }

    private TriggerCandidate selectBestTrigger(
            List<Map<String, Object>> triggers,
            String merchantId,
            String category,
            Map<String, Object> merchantPayload
    ) {

        if (triggers == null || triggers.isEmpty()) {

            return new TriggerCandidate(
                    Map.of(
                            "trigger_id",
                            "perf_dip",
                            "id",
                            "perf_dip",
                            "kind",
                            "perf_dip",
                            "payload",
                            Map.of()
                    ),
                    "perf_dip",
                    "perf_dip",
                    Map.of(),
                    0
            );
        }

        List<TriggerCandidate> scored =
                new ArrayList<>();

        for (Map<String, Object> trigger : triggers) {

            if (trigger == null) {
                continue;
            }

            if (!appliesToMerchant(
                    trigger,
                    merchantId
            )) {
                continue;
            }

            scored.add(
                    scoreTrigger(
                            trigger,
                            merchantId,
                            category,
                            merchantPayload
                    )
            );
        }

        if (scored.isEmpty()) {
            return null;
        }

        scored.sort(
                Comparator
                        .comparingInt(
                                TriggerCandidate::score
                        )
                        .reversed()
                        .thenComparing(
                                candidate ->
                                        candidate.triggerId,
                                Comparator.nullsLast(
                                        String::compareTo
                                )
                        )
        );

        TriggerCandidate best =
                scored.get(0);

        if (scored.size() > 1) {

            TriggerCandidate runnerUp =
                    scored.get(1);

            int previousSelections =
                    store.getSelectionCount(
                            best.triggerId
                    );

            if (previousSelections > 0
                    && best.score - runnerUp.score < 8) {

                best = runnerUp;
            }
        }

        return best;
    }

    private boolean appliesToMerchant(
            Map<String, Object> trigger,
            String merchantId
    ) {

        Map<String, Object> payload =
                cast(trigger.get("payload"));

        String targetMerchantId = payload == null
                ? null
                : stringValue(payload.get("merchant_id"));

        if (targetMerchantId == null) {
            targetMerchantId = stringValue(
                    trigger.get("merchant_id")
            );
        }

        /*
         * Triggers without a merchant target are global and can be scored
         * for every merchant. A targeted trigger is valid only for its
         * exact merchant.
         */
        return targetMerchantId == null
                || targetMerchantId.equals(merchantId);
    }

    private TriggerCandidate scoreTrigger(
            Map<String, Object> trigger,
            String merchantId,
            String category,
            Map<String, Object> merchantPayload
    ) {

        Map<String, Object> payload =
                cast(trigger.get("payload"));

        if (payload == null) {
            payload = new HashMap<>();
        }

        String triggerId =
                firstNonNull(
                        stringValue(
                                trigger.get("trigger_id")
                        ),
                        stringValue(
                                trigger.get("id")
                        ),
                        "perf_dip"
                );

        String triggerKind =
                firstNonNull(
                        stringValue(
                                trigger.get("kind")
                        ),
                        inferKindFromId(
                                triggerId
                        )
                );

        int score = 0;

        score += merchantSpecificityScore(
                merchantId,
                payload
        );

        score += urgencyScore(
                payload,
                triggerKind,
                triggerId
        );

        score += freshnessScore(
                payload
        );

        score += businessImpactScore(
                payload,
                merchantPayload
        );

        score += performanceImpactScore(
                payload,
                merchantPayload
        );

        score += categoryRelevanceScore(
                category,
                triggerKind
        );

        score += actionableSignalScore(
                payload,
                merchantPayload
        );

        score -= repetitionPenalty(
                triggerId,
                payload
        );

        return new TriggerCandidate(
                trigger,
                triggerId,
                triggerKind,
                payload,
                score
        );
    }

    private int merchantSpecificityScore(
            String merchantId,
            Map<String, Object> payload
    ) {

        if (merchantId == null
                || merchantId.isBlank()) {

            return 0;
        }

        if (merchantId.equals(
                payload.get("merchant_id")
        )) {

            return 12;
        }

        return 0;
    }

    private int urgencyScore(
            Map<String, Object> payload,
            String triggerKind,
            String triggerId
    ) {

        int score = 0;

        int urgency =
                getInt(
                        payload,
                        "urgency",
                        0
                );

        if (urgency > 0) {

            score += Math.min(
                    urgency * 2,
                    36
            );
        }

        int daysLeft =
                getInt(
                        payload,
                        "days_left",
                        0
                );

        if (daysLeft > 0
                && daysLeft <= 3) {

            score += 8;
        }

        if ("recall_due".equals(triggerKind)
                || (
                triggerId != null
                        && triggerId
                        .toLowerCase()
                        .contains("recall")
        )) {

            score += 12;
        }

        if ("perf_dip".equals(triggerKind)
                || (
                triggerId != null
                        && triggerId
                        .toLowerCase()
                        .contains("perf_dip")
        )) {

            score += 10;
        }

        return score;
    }

    private int freshnessScore(
            Map<String, Object> payload
    ) {

        int explicitAgeDays =
                getInt(
                        payload,
                        "age_days",
                        -1
                );

        if (explicitAgeDays >= 0) {

            return Math.max(
                    0,
                    18 - explicitAgeDays
            );
        }

        for (String key : List.of(
                "timestamp",
                "triggered_at",
                "created_at",
                "updated_at",
                "event_time",
                "occurred_at",
                "last_seen_at"
        )) {

            Object value =
                    payload.get(key);

            if (value == null) {
                continue;
            }

            try {

                Instant instant =
                        Instant.parse(
                                value.toString()
                        );

                long ageMillis =
                        System.currentTimeMillis()
                                - instant.toEpochMilli();

                long computedAgeDays =
                        ageMillis / 86400000L;

                return Math.max(
                        0,
                        18 - (int) computedAgeDays
                );

            } catch (Exception ignored) {
                // Ignore malformed timestamp inputs.
            }
        }

        return 0;
    }

    private int businessImpactScore(
            Map<String, Object> payload,
            Map<String, Object> merchantPayload
    ) {

        int score = 0;

        String[] keys = {
                "due_count",
                "affected_customers",
                "affected_count",
                "customers_affected",
                "orders_at_risk",
                "bookings_due"
        };

        for (String key : keys) {

            int value =
                    getInt(
                            payload,
                            key,
                            0
                    );

            if (value == 0
                    && merchantPayload != null) {

                value =
                        getInt(
                                cast(
                                        merchantPayload
                                                .get("metrics")
                                ),
                                key,
                                0
                        );
            }

            if (value > 0) {

                score += Math.min(
                        value * 8,
                        90
                );
            }
        }

        String[] revenueKeys = {
                "revenue_at_risk",
                "potential_revenue",
                "estimated_revenue_loss",
                "financial_impact"
        };

        for (String key : revenueKeys) {

            int value =
                    getInt(
                            payload,
                            key,
                            0
                    );

            if (value > 0) {

                score += Math.min(
                        value / 100,
                        30
                );
            }
        }

        return score;
    }

    private int performanceImpactScore(
            Map<String, Object> payload,
            Map<String, Object> merchantPayload
    ) {

        int score = 0;

        int dropPercent =
                getInt(
                        payload,
                        "drop_percent",
                        0
                );

        if (dropPercent > 0) {

            score += Math.min(
                    dropPercent * 2,
                    36
            );
        }

        int openSlots =
                getInt(
                        payload,
                        "open_slots",
                        0
                );

        if (openSlots == 0
                && merchantPayload != null) {

            openSlots =
                    getInt(
                            cast(
                                    merchantPayload
                                            .get("performance")
                            ),
                            "open_slots",
                            0
                    );
        }

        if (openSlots > 0) {

            score += Math.min(
                    openSlots * 2,
                    16
            );
        }

        return score;
    }

    private int categoryRelevanceScore(
            String category,
            String triggerKind
    ) {

        if (category == null
                || category.isBlank()) {

            return 0;
        }

        return switch (category) {

            case "restaurants" ->
                    triggerKind != null
                            && (
                            triggerKind.contains("perf")
                                    || triggerKind.contains("review")
                                    || triggerKind.contains("festival")
                                    || triggerKind.contains("digest")
                    )
                            ? 14
                            : 6;

            case "salons" ->
                    triggerKind != null
                            && (
                            triggerKind.contains("perf")
                                    || triggerKind.contains("review")
                                    || triggerKind.contains("festival")
                                    || triggerKind.contains("digest")
                    )
                            ? 14
                            : 6;

            case "dentists" ->
                    triggerKind != null
                            && (
                            triggerKind.contains("recall")
                                    || triggerKind.contains("review")
                                    || triggerKind.contains("regulation")
                    )
                            ? 18
                            : 6;

            case "gyms" ->
                    triggerKind != null
                            && (
                            triggerKind.contains("dormant")
                                    || triggerKind.contains("milestone")
                                    || triggerKind.contains("perf")
                    )
                            ? 18
                            : 6;

            case "pharmacies" ->
                    triggerKind != null
                            && (
                            triggerKind.contains("recall")
                                    || triggerKind.contains("regulation")
                                    || triggerKind.contains("perf")
                    )
                            ? 18
                            : 6;

            default -> 4;
        };
    }

    private int actionableSignalScore(
            Map<String, Object> payload,
            Map<String, Object> merchantPayload
    ) {

        int score = 0;

        if (getInt(
                payload,
                "due_count",
                0
        ) > 0) {

            score += 10;
        }

        if (getInt(
                payload,
                "affected_count",
                0
        ) > 0) {

            score += 10;
        }

        if (merchantPayload != null) {

            Map<String, Object> signals =
                    cast(
                            merchantPayload.get(
                                    "signals"
                            )
                    );

            if (signals != null
                    && Boolean.TRUE.equals(
                    signals.get(
                            "trial_ending_soon"
                    )
            )) {

                score += 8;
            }
        }

        if (getInt(
                payload,
                "open_slots",
                0
        ) > 0) {

            score += 6;
        }

        return score;
    }

    private int repetitionPenalty(
            String triggerId,
            Map<String, Object> payload
    ) {

        if (triggerId == null
                || triggerId.isBlank()) {

            return 0;
        }

        int penalty = 0;

        if (store.wasRecentlySelected(
                triggerId,
                300000L
        )) {

            penalty += 18;
        }

        if (store.wasActioned(triggerId)) {

            penalty += 28;
        }

        if (payload != null
                && Boolean.TRUE.equals(
                payload.get("already_acted")
        )) {

            penalty += 25;
        }

        if (payload != null
                && Boolean.TRUE.equals(
                payload.get("actioned")
        )) {

            penalty += 25;
        }

        return penalty;
    }

    private String generateMessage(
            String triggerKind,
            String merchantName,
            String ownerName,
            String category,
            int due,
            int drop,
            int days,
            String topItemId,
            boolean isNew,
            boolean trialEnding,
            boolean iplEligible,
            String perfStatus,
            int openSlots,
            String catPeerCtr,
            String digestTitle,
            String digestSource
    ) {

        String addr = merchantName;

        if (addr == null
                || addr.isBlank()
                || "your store".equals(addr)) {

            addr = ownerName;
        }

        if (addr == null
                || addr.isBlank()) {

            addr = "your store";
        }

        String issue =
                buildIssueLine(
                        triggerKind,
                        category,
                        due,
                        drop,
                        days,
                        topItemId,
                        isNew,
                        trialEnding,
                        iplEligible,
                        perfStatus,
                        openSlots,
                        catPeerCtr,
                        digestTitle,
                        digestSource
                );

        String action =
                buildAction(
                        triggerKind,
                        category,
                        due,
                        openSlots,
                        topItemId
                );

        String reason =
                buildWhyNow(
                        triggerKind,
                        drop,
                        days,
                        isNew,
                        trialEnding,
                        iplEligible,
                        perfStatus
                );

        return addr
                + ", "
                + issue
                + " "
                + reason
                + ". "
                + action
                + " Reply YES.";
    }

    private String buildAction(
            String triggerKind,
            String category,
            int due,
            int openSlots,
            String topItemId
    ) {

        if (isAvailabilityTrigger(triggerKind)
                && "salons".equals(category)
                && openSlots > 0) {

            return "I can help you fill the "
                    + openSlots
                    + " open appointment slots.";
        }

        if (isRecallTrigger(triggerKind)
                && "dentists".equals(category)) {

            return "I can help you prepare patient follow-up outreach.";
        }

        if (isRecallTrigger(triggerKind)
                && "pharmacies".equals(category)) {

            return "I can help you prepare refill reminders.";
        }

        if (isDormantTrigger(triggerKind)
                && "gyms".equals(category)) {

            return "I can help you prepare a member re-engagement message.";
        }

        if (isPerformanceTrigger(triggerKind)
                && "restaurants".equals(category)) {

            return "I can help you respond to the restaurant performance signal.";
        }

        if (due > 0
                && isRecallTrigger(triggerKind)) {

            return "I can help you prepare follow-up outreach.";
        }

        if (topItemId != null
                && !topItemId.isBlank()) {

            return "I can help you act on the supplied item context.";
        }

        return "I can help you review this signal.";
    }

    private String buildIssueLine(
            String triggerKind,
            String category,
            int due,
            int drop,
            int days,
            String topItemId,
            boolean isNew,
            boolean trialEnding,
            boolean iplEligible,
            String perfStatus,
            int openSlots,
            String catPeerCtr,
            String digestTitle,
            String digestSource
    ) {

        StringBuilder issue =
                new StringBuilder();

        if ("recall_due".equals(triggerKind)
                || (
                triggerKind != null
                        && triggerKind.contains("recall")
        )) {

            if (due > 0) {

                issue.append(due)
                        .append(" follow-ups are due");

            } else {

                issue.append(
                        "a recall follow-up signal is available"
                );
            }

        } else if ("perf_dip".equals(triggerKind)
                || (
                triggerKind != null
                        && triggerKind.contains("perf")
        )) {

            if (drop > 0) {

                issue.append(
                                "performance is down "
                        )
                        .append(drop)
                        .append("%");

            } else {

                issue.append(
                        "a performance signal is available"
                );
            }

        } else if ("festival_upcoming".equals(triggerKind)) {

            issue.append(
                    "a festival signal is available"
            );

        } else if ("dormant_with_vera".equals(triggerKind)) {

            issue.append(
                    "a customer inactivity signal is available"
            );

        } else if ("research_digest".equals(triggerKind)
                || (
                triggerKind != null
                        && triggerKind.contains("digest")
        )) {

            if (digestTitle != null) {

                issue.append(
                                "a category insight is available: "
                        )
                        .append(digestTitle);

            } else {

                issue.append(
                        "a category insight signal is available"
                );
            }

        } else if ("review_theme_emerged".equals(triggerKind)) {

            issue.append(
                    "a customer feedback signal is available"
            );

        } else {

            issue.append(
                    "a business signal is available"
            );
        }

        if (topItemId != null
                && !topItemId.isBlank()) {

            issue.append(" for ")
                    .append(topItemId);
        }

        if ("salons".equals(category)
                && isAvailabilityTrigger(triggerKind)
                && openSlots > 0) {

            issue.append(" with ")
                    .append(openSlots)
                    .append(" appointment slots open");
        }

        if ("pharmacies".equals(category)
                && isRecallTrigger(triggerKind)) {

            issue.append(
                    " for refill follow-up"
            );
        }

        if ("dentists".equals(category)
                && isRecallTrigger(triggerKind)) {

            issue.append(
                    " for patient follow-up"
            );
        }

        if ("gyms".equals(category)
                && isDormantTrigger(triggerKind)) {

            issue.append(
                    " for member re-engagement"
            );
        }

        if ("restaurants".equals(category)
                && isPerformanceTrigger(triggerKind)) {

            issue.append(
                    " for restaurant demand review"
            );
        }

        return issue.toString();
    }

    private String buildWhyNow(
            String triggerKind,
            int drop,
            int days,
            boolean isNew,
            boolean trialEnding,
            boolean iplEligible,
            String perfStatus
    ) {

        if (triggerKind == null) {

            return "the selected signal is available";
        }

        if (isPerformanceTrigger(triggerKind)) {

            if (drop > 0) {

                return "the supplied performance change is the reason to review it";
            }

            return "the performance signal is available for review";
        }

        if (isRecallTrigger(triggerKind)) {

            if (days > 0) {

                return "the supplied follow-up window has "
                        + days
                        + " day"
                        + (days == 1 ? "" : "s")
                        + " remaining";
            }

            return "the recall signal is available for review";
        }

        if ("festival_upcoming".equals(triggerKind)) {

            return "the festival signal can be reviewed";
        }

        if (isDormantTrigger(triggerKind)) {

            return "the inactivity signal can be reviewed";
        }

        if ("research_digest".equals(triggerKind)
                || triggerKind.contains("digest")) {

            return "the supplied category insight can be reviewed";
        }

        if ("review_theme_emerged".equals(triggerKind)) {

            return "the supplied customer feedback can be reviewed";
        }

        if (trialEnding) {

            return "the supplied trial-ending signal can be reviewed";
        }

        if (iplEligible) {

            return "the supplied local eligibility signal can be reviewed";
        }

        if (perfStatus != null
                && !perfStatus.isBlank()) {

            return "the supplied status is "
                    + perfStatus;
        }

        return "the selected signal is available for review";
    }

    private String buildRationale(
            String triggerKind,
            String category,
            String merchantId,
            int due,
            int drop,
            int days,
            Map<String, Object> payload
    ) {

        StringBuilder rationale =
                new StringBuilder();

        rationale.append("Selected ")
                .append(
                        describeTrigger(
                                triggerKind
                        )
                );

        if (merchantId != null
                && !merchantId.isBlank()) {

            rationale.append(
                            " for merchant "
                    )
                    .append(merchantId);
        }

        if (category != null
                && !category.isBlank()) {

            rationale.append(
                            " in category "
                    )
                    .append(category);
        }

        boolean hasEvidence = false;

        if (due > 0) {

            rationale.append(
                            " with "
                    )
                    .append(due)
                    .append(" follow-ups due");

            hasEvidence = true;
        }

        if (drop > 0) {

            rationale.append(
                            hasEvidence
                                    ? " and "
                                    : " with "
                    )
                    .append(
                            "performance down "
                    )
                    .append(drop)
                    .append("%");

            hasEvidence = true;
        }

        if (days > 0) {

            rationale.append(
                            hasEvidence
                                    ? " and "
                                    : " with "
                    )
                    .append(days)
                    .append(" day(s) remaining");

            hasEvidence = true;
        }

        if (payload != null
                && getInt(
                payload,
                "urgency",
                0
        ) > 0) {

            rationale.append(
                            hasEvidence
                                    ? ", "
                                    : " with "
                    )
                    .append(
                            "an urgency signal"
                    );

            hasEvidence = true;
        }

        if (payload != null
                && (
                payload.containsKey("age_days")
                        || hasTimestamp(payload)
        )) {

            rationale.append(
                            hasEvidence
                                    ? ", "
                                    : " with "
                    )
                    .append(
                            "freshness context"
                    );

            hasEvidence = true;
        }

        if (payload != null
                && payload.get("merchant_id") != null) {

            rationale.append(
                            hasEvidence
                                    ? ", "
                                    : " with "
                    )
                    .append(
                            "a merchant-specific match"
                    );

            hasEvidence = true;
        }

        if (category != null
                && !category.isBlank()) {

            rationale.append(
                            hasEvidence
                                    ? ", "
                                    : " with "
                    )
                    .append(
                            "category context for "
                    )
                    .append(category);
        }

        rationale.append(".");

        return rationale.toString();
    }

    private String describeTrigger(
            String triggerKind
    ) {

        if (isRecallTrigger(triggerKind)) {

            return "a recall follow-up signal";
        }

        if (isPerformanceTrigger(triggerKind)) {

            return "a performance signal";
        }

        if (isDormantTrigger(triggerKind)) {

            return "an inactivity signal";
        }

        if (triggerKind != null
                && triggerKind.contains("digest")) {

            return "a category insight signal";
        }

        if (triggerKind != null
                && triggerKind.contains("review")) {

            return "a customer feedback signal";
        }

        if (triggerKind != null
                && triggerKind.contains("festival")) {

            return "a festival signal";
        }

        return "a business signal";
    }

    private boolean hasTimestamp(
            Map<String, Object> payload
    ) {

        return List.of(
                        "timestamp",
                        "triggered_at",
                        "created_at",
                        "updated_at",
                        "event_time",
                        "occurred_at",
                        "last_seen_at"
                )
                .stream()
                .anyMatch(
                        payload::containsKey
                );
    }

    private boolean isRecallTrigger(
            String triggerKind
    ) {

        return triggerKind != null
                && triggerKind.contains("recall");
    }

    private boolean isPerformanceTrigger(
            String triggerKind
    ) {

        return triggerKind != null
                && triggerKind.contains("perf");
    }

    private boolean isDormantTrigger(
            String triggerKind
    ) {

        return "dormant_with_vera".equals(
                triggerKind
        );
    }

    private boolean isAvailabilityTrigger(
            String triggerKind
    ) {

        return triggerKind != null
                && (
                triggerKind.contains("availability")
                        || triggerKind.contains("slot")
        );
    }

    private static final class TriggerCandidate {

        private final Map<String, Object> trigger;
        private final String triggerId;
        private final String triggerKind;
        private final Map<String, Object> payload;
        private final int score;

        private TriggerCandidate(
                Map<String, Object> trigger,
                String triggerId,
                String triggerKind,
                Map<String, Object> payload,
                int score
        ) {

            this.trigger = trigger;
            this.triggerId = triggerId;
            this.triggerKind = triggerKind;
            this.payload = payload;
            this.score = score;
        }

        public int score() {
            return score;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Object o) {

        if (o instanceof Map<?, ?> map) {

            return (Map<String, Object>) map;
        }

        return null;
    }

    private String inferKindFromId(
            String triggerId
    ) {

        if (triggerId == null) {
            return "perf_dip";
        }

        String lower =
                triggerId.toLowerCase();

        if (lower.contains("research")
                || lower.contains("digest")) {

            return "research_digest";
        }

        if (lower.contains("recall")) {

            return "recall_due";
        }

        if (lower.contains("perf_dip")) {

            return "perf_dip";
        }

        if (lower.contains("perf_spike")) {

            return "perf_spike";
        }

        if (lower.contains("festival")) {

            return "festival_upcoming";
        }

        if (lower.contains("competitor")) {

            return "competitor_opened";
        }

        if (lower.contains("regulation")) {

            return "regulation_change";
        }

        if (lower.contains("dormant")) {

            return "dormant_with_vera";
        }

        if (lower.contains("milestone")) {

            return "milestone_reached";
        }

        if (lower.contains("review")) {

            return "review_theme_emerged";
        }

        return "perf_dip";
    }

    private String firstNonNull(
            String... values
    ) {

        for (String value : values) {

            if (value != null
                    && !value.isBlank()) {

                return value;
            }
        }

        return "perf_dip";
    }

    private String stringValue(
            Object value
    ) {

        if (value == null) {
            return null;
        }

        String string =
                value.toString();

        return string.isBlank()
                ? null
                : string;
    }

    private int getInt(
            Map<String, Object> map,
            String key,
            int def
    ) {

        if (map == null) {
            return def;
        }

        Object value =
                map.get(key);

        if (value == null) {
            return def;
        }

        try {

            if (value instanceof Number number) {

                return number.intValue();
            }

            return Integer.parseInt(
                    value.toString()
            );

        } catch (Exception e) {

            return def;
        }
    }

    private String suppressionKey(
            Map<String, Object> trigger,
            Map<String, Object> payload,
            String triggerId,
            String merchantId
    ) {

        String explicit = trigger == null
                ? null
                : stringValue(trigger.get("suppression_key"));

        if (explicit == null && payload != null) {
            explicit = stringValue(payload.get("suppression_key"));
        }

        if (explicit != null) {
            return explicit;
        }

        String safeTriggerId = triggerId == null || triggerId.isBlank()
                ? "unknown"
                : triggerId;

        String safeMerchantId = merchantId == null || merchantId.isBlank()
                ? "unknown"
                : merchantId;

        return "trigger:" + safeTriggerId + ":merchant:" + safeMerchantId;
    }
}
