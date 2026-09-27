package com.magicpin.bot.service;

import java.util.List;
import java.util.Map;

public interface KnowledgeRetriever {

    List<KnowledgeItem> retrieve(
            String query,
            Map<String, Object> context,
            int limit
    );
}