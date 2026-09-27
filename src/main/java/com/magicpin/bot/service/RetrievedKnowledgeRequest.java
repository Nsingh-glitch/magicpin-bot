package com.magicpin.bot.service;

/**
 * Gemini-facing representation of approved retrieved knowledge.
 *
 * Deliberately excludes internal identifiers such as:
 * merchant_id, context_id, trigger_id and trigger_kind.
 */
public record RetrievedKnowledgeRequest(
        String title,
        String content,
        String source,
        String category
) {
}