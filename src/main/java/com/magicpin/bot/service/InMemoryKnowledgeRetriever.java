package com.magicpin.bot.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class InMemoryKnowledgeRetriever implements KnowledgeRetriever {

    private final List<KnowledgeItem> knowledge = List.of(
            new KnowledgeItem(
                    "Restaurant open-slot guidance",
                    "Restaurant businesses can use open appointment or service slots as a signal for demand and outreach planning.",
                    "approved-demo-guidance",
                    "restaurants",
                    null,
                    null,
                    true
            ),
            new KnowledgeItem(
                    "Salon appointment guidance",
                    "Salon businesses can use available appointment slots when considering customer re-engagement.",
                    "approved-demo-guidance",
                    "salons",
                    null,
                    null,
                    true
            ),
            new KnowledgeItem(
                    "Dentist recall guidance",
                    "Dental practices can use due recall or follow-up signals when preparing customer communication.",
                    "approved-demo-guidance",
                    "dentists",
                    null,
                    null,
                    true
            ),
            new KnowledgeItem(
                    "Gym member re-engagement guidance",
                    "Gyms can use member engagement signals when preparing appropriate outreach.",
                    "approved-demo-guidance",
                    "gyms",
                    null,
                    null,
                    true
            ),
            new KnowledgeItem(
                    "Pharmacy refill guidance",
                    "Pharmacies can use refill or recall signals when preparing relevant customer communication.",
                    "approved-demo-guidance",
                    "pharmacies",
                    null,
                    null,
                    true
            )
    );

    @Override
    public List<KnowledgeItem> retrieve(
            String query,
            Map<String, Object> context,
            int limit
    ) {
        if (limit <= 0) {
            return List.of();
        }

        String safeQuery = query == null ? "" : query.toLowerCase();

        String category = stringValue(context, "category");
        String merchantId = stringValue(context, "merchant_id");
        String contextId = stringValue(context, "context_id");

        return knowledge.stream()
                .filter(KnowledgeItem::approved)
                .filter(item ->
                        matchesScope(item, merchantId, contextId)
                                || matchesCategory(item, category)
                                || keywordMatch(item, safeQuery))
                .sorted(
                        Comparator
                                .comparingInt((KnowledgeItem item) ->
                                        relevance(item, safeQuery, category, merchantId, contextId))
                                .reversed()
                                .thenComparing(
                                        KnowledgeItem::title,
                                        Comparator.nullsLast(String::compareTo)
                                )
                )
                .limit(limit)
                .toList();
    }

    private boolean matchesScope(
            KnowledgeItem item,
            String merchantId,
            String contextId
    ) {
        boolean merchantMatch =
                merchantId != null
                        && !merchantId.isBlank()
                        && merchantId.equals(item.merchantId());

        boolean contextMatch =
                contextId != null
                        && !contextId.isBlank()
                        && contextId.equals(item.contextId());

        return merchantMatch || contextMatch;
    }

    private boolean matchesCategory(
            KnowledgeItem item,
            String category
    ) {
        return category != null
                && !category.isBlank()
                && category.equalsIgnoreCase(item.category());
    }

    private boolean keywordMatch(
            KnowledgeItem item,
            String query
    ) {
        if (query == null || query.isBlank()) {
            return false;
        }

        String text = (
                safe(item.title()) + " "
                        + safe(item.content()) + " "
                        + safe(item.category())
        ).toLowerCase();

        return List.of(query.split("\\s+"))
                .stream()
                .filter(token -> token.length() >= 3)
                .anyMatch(text::contains);
    }

    private int relevance(
            KnowledgeItem item,
            String query,
            String category,
            String merchantId,
            String contextId
    ) {
        int score = 0;

        if (merchantId != null
                && merchantId.equals(item.merchantId())) {
            score += 100;
        }

        if (contextId != null
                && contextId.equals(item.contextId())) {
            score += 90;
        }

        if (category != null
                && category.equalsIgnoreCase(item.category())) {
            score += 50;
        }

        if (keywordMatch(item, query)) {
            score += 10;
        }

        return score;
    }

    private String stringValue(
            Map<String, Object> context,
            String key
    ) {
        if (context == null) {
            return null;
        }

        Object value = context.get(key);

        return value == null ? null : String.valueOf(value);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}