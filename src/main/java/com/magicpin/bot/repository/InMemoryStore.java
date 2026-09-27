package com.magicpin.bot.repository;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryStore {

    private final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();
    private final Map<String, Integer> triggerSelectionCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> triggerLastSelectedAt = new ConcurrentHashMap<>();
    private final Map<String, Boolean> triggerActioned = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> latestDecisions = new ConcurrentHashMap<>();

    public void save(String key, Map<String, Object> data) {
        store.put(key, data);
    }

    public void saveContext(String scope, String contextId, Map<String, Object> data) {
        store.put(contextKey(scope, contextId), data);
    }

    public Collection<Map<String, Object>> getAll() {
        return store.values();
    }

    public Map<String, Object> getByKey(String key) {
        return store.get(key);
    }

    public Map<String, Object> getContext(String scope, String contextId) {
        Map<String, Object> context = store.get(contextKey(scope, contextId));

        /*
         * Preserve the original low-level helper for existing in-process
         * callers and older tests. HTTP-loaded contexts always use the
         * collision-safe key above.
         */
        return context != null ? context : store.get(scope + ":" + contextId);
    }

    public void recordLatestDecision(String scope, String contextId, String triggerId,
                                     String triggerKind, String merchantId, String body) {
        if (scope == null || scope.isBlank() || contextId == null || contextId.isBlank()) {
            return;
        }
        Map<String, Object> decision = new ConcurrentHashMap<>();
        decision.put("scope", scope);
        decision.put("context_id", contextId);
        if (triggerId != null && !triggerId.isBlank()) {
            decision.put("trigger_id", triggerId);
        }
        if (triggerKind != null && !triggerKind.isBlank()) {
            decision.put("trigger_kind", triggerKind);
        }
        if (merchantId != null && !merchantId.isBlank()) {
            decision.put("merchant_id", merchantId);
        }
        if (body != null && !body.isBlank()) {
            decision.put("body", body);
        }
        decision.put("actioned", false);
        latestDecisions.put(contextKey(scope, contextId), decision);
    }

    public Map<String, Object> getLatestDecision(String scope, String contextId) {
        if (scope == null || scope.isBlank() || contextId == null || contextId.isBlank()) {
            return null;
        }
        Map<String, Object> decision = latestDecisions.get(contextKey(scope, contextId));
        return decision != null ? decision : latestDecisions.get(scope + ":" + contextId);
    }

    public void recordTriggerSelection(String triggerId) {
        if (triggerId == null || triggerId.isBlank()) {
            return;
        }
        int count = triggerSelectionCounts.getOrDefault(triggerId, 0) + 1;
        triggerSelectionCounts.put(triggerId, count);
        triggerLastSelectedAt.put(triggerId, System.currentTimeMillis());
    }

    public void recordTriggerAction(String triggerId) {
        if (triggerId == null || triggerId.isBlank()) {
            return;
        }
        triggerActioned.put(triggerId, true);
        recordTriggerSelection(triggerId);
    }

    public int getSelectionCount(String triggerId) {
        return triggerSelectionCounts.getOrDefault(triggerId, 0);
    }

    public boolean wasRecentlySelected(String triggerId, long withinMillis) {
        if (triggerId == null || triggerId.isBlank()) {
            return false;
        }
        Long lastSelected = triggerLastSelectedAt.get(triggerId);
        if (lastSelected == null) {
            return false;
        }
        return (System.currentTimeMillis() - lastSelected) <= withinMillis;
    }

    public boolean wasActioned(String triggerId) {
        return triggerId != null && Boolean.TRUE.equals(triggerActioned.get(triggerId));
    }

    private String contextKey(String scope, String contextId) {
        String safeScope = scope == null ? "" : scope;
        String safeContextId = contextId == null ? "" : contextId;
        int scopeLength = scope == null ? -1 : safeScope.length();
        int contextLength = contextId == null ? -1 : safeContextId.length();
        return "context|" + scopeLength + "|" + safeScope
                + "|" + contextLength + "|" + safeContextId;
    }
}
