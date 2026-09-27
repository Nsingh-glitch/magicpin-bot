package com.magicpin.bot.service;

public record KnowledgeItem(
        String title,
        String content,
        String source,
        String category,
        String merchantId,
        String contextId,
        boolean approved
) {
}