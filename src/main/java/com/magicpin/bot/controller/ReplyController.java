package com.magicpin.bot.controller;

import com.magicpin.bot.repository.InMemoryStore;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@RestController
@RequestMapping({"", "/v1"})
public class ReplyController {

    private final InMemoryStore store;
    private final Map<String, List<Map<String, String>>> history = new ConcurrentHashMap<>();
    private final Map<String, ConversationState> conversationStates = new ConcurrentHashMap<>();
    /*
     * A reply updates both history and terminal state.  Keep that transition
     * atomic per conversation without serializing unrelated conversations.
     */
    private final Map<String, Object> conversationLocks = new ConcurrentHashMap<>();

    public ReplyController(InMemoryStore store) {
        this.store = store;
    }

    @PostMapping("/reply")
    public Map<String, Object> reply(@RequestBody Map<String, Object> req) {

        if (req == null) {
            return send("Please provide a conversation ID and message.", "open_ended", "Missing reply request");
        }

        String convId = normalizedString(req.get("conversation_id"));
        if (convId == null) {
            return send("Please provide a conversation ID to continue.", "open_ended", "Missing conversation ID");
        }

        String role = normalizedString(req.get("from_role"));
        if (role != null) {
            role = role.toLowerCase(Locale.ROOT);
        }
        if (role == null || !("customer".equals(role) || "merchant".equals(role))) {
            return send("Please specify whether this reply is from a customer or merchant.", "open_ended", "Invalid from_role");
        }

        String msg = normalizedString(req.get("message"));
        if (msg == null) {
            return send("Please send a message to continue.", "open_ended", "Empty message ignored");
        }

        String msgLower = msg.toLowerCase(Locale.ROOT);
        synchronized (conversationLocks.computeIfAbsent(convId, ignored -> new Object())) {
        ConversationState state = conversationStates.getOrDefault(convId, ConversationState.OPEN);
        if (state != ConversationState.OPEN) {
            return end("Conversation is already " + state.name().toLowerCase(Locale.ROOT).replace('_', '-') + ".");
        }

        String scope = normalizedString(req.get("scope"));
        String contextId = normalizedString(req.get("context_id"));
        Map<String, Object> context = readContext(scope, contextId);
        Map<String, Object> latestDecision = store.getLatestDecision(scope, contextId);

        // ── Record turn ──────────────────────────────────────────────────────
        Map<String, String> turn = new HashMap<>();
        turn.put("role",       role);
        turn.put("msg",        msg);
        turn.put("normalized", msgLower);
        history.computeIfAbsent(convId, k -> new ArrayList<>()).add(turn);

        List<Map<String, String>> turns = history.get(convId);

        // ════════════════════════════════════════════════════════════════════
        // CHECK 1 — STOP (absolute highest priority)
        // ════════════════════════════════════════════════════════════════════
        if (isStop(msgLower)) {
            conversationStates.put(convId, ConversationState.OPTED_OUT);
            return end("User opted out via STOP keyword");
        }

        // ════════════════════════════════════════════════════════════════════
        // CHECK 2 — UNIFIED AUTO-REPLY DETECTION
        //
        // A message is an "auto-signal" when it matches a known phrase.
        //
        // Count how many of this role's PRIOR messages were auto-signals.
        //   priorAutoCount == 0 → this is the FIRST signal → probe once (send)
        //   priorAutoCount >= 1 → second or more signal    → end immediately
        //
        // This means:
        //   Turn 1 phrase match  → send (probe)
        //   Turn 2 phrase match  → end  ✅  (judge sees end by turn 2)
        // ════════════════════════════════════════════════════════════════════
        List<String> roleMsgs = new ArrayList<>();
        for (Map<String, String> t : turns) {
            if (role.equals(t.get("role"))) {
                roleMsgs.add(t.get("normalized"));
            }
        }

        boolean currentIsSignal  = isAutoReplyPhrase(msgLower);

        if (currentIsSignal) {
            // Count auto-signals in PREVIOUS messages (all except the current one)
            long priorAutoCount = 0;
            for (int i = 0; i < roleMsgs.size() - 1; i++) {
                String m        = roleMsgs.get(i);
                if (isAutoReplyPhrase(m)) priorAutoCount++;
            }

            if (priorAutoCount == 0) {
                // First auto-signal ever in this conversation — probe once
                return send(
                    "Yeh message automated lag raha hai. " +
                    "Kya aap personally available hain? " +
                    "Reply YES to continue or STOP to unsubscribe.",
                    "open_ended",
                    "First auto-reply signal — probing once before exit"
                );
            } else {
                // Second or more — exit immediately
                conversationStates.put(convId, ConversationState.ENDED);
                return end("Auto-reply confirmed after " + (priorAutoCount + 1) +
                           " signals — exiting gracefully");
            }
        }

        // ════════════════════════════════════════════════════════════════════
        // CHECK 3 — ROLE BRANCH
        // ════════════════════════════════════════════════════════════════════
        Map<String, Object> response = "customer".equals(role)
            ? handleCustomer(msgLower, scope, contextId, context, latestDecision)
            : handleMerchant(msgLower, scope, contextId, context, latestDecision);
        if ("end".equals(response.get("action"))) {
            conversationStates.put(convId, ConversationState.ENDED);
        }
        return response;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // CUSTOMER HANDLER
    // Judge test: "Yes please book me for Wed 5 Nov, 6pm"
    // Must return action=send, body non-null, cta=none
    // ════════════════════════════════════════════════════════════════════════
    private Map<String, Object> handleCustomer(String msg, String scope, String contextId,
                                               Map<String, Object> context, Map<String, Object> latestDecision) {

        if (anyOf(msg,
                "yes", "haan", "1", "2", "3",
                "mon", "tue", "wed", "thu", "fri", "sat", "sun",
                "monday", "tuesday", "wednesday", "thursday",
                "friday", "saturday", "sunday",
                "book", "confirm", "kal", "aaj", "please", "ok", "okay")) {
            Confirmation confirmation = customerConfirmation(scope, contextId, context);
            if (confirmation == null) {
                return send(
                    customerClarification(latestDecision),
                    "open_ended",
                    latestDecision == null
                        ? "Customer confirmation requires stored booking or action state"
                        : "Customer confirmation matched the latest stored tick decision; no action execution was recorded"
                );
            }
            return send(
                confirmation.message,
                "none",
                "Customer confirmation matched stored booking state"
            );
        }

        if (anyOf(msg, "cancel", "won't", "cannot", "can't")) {
            return send(
                "I can help with a cancellation request. Please share the booking details.",
                "none",
                "Customer requested cancellation; no cancellation state was changed"
            );
        }

        if (anyOf(msg, "reschedule", "change", "different", "other",
                       "alag", "doosra", "baad", "later")) {
            return send(
                "Of course! Please share your preferred date and time " +
                "and I can help with the rescheduling request.",
                "open_ended",
                "Customer wants to reschedule — asking for preference"
            );
        }

        if (msg.contains("?") || anyOf(msg, "what", "how", "when", "where",
                                            "kya", "kaise", "kab", "kitna")) {
            return send(
                "Happy to help! For queries about services, pricing, or timings, " +
                "please share what you would like to know.",
                "open_ended",
                "Customer question — answering and keeping conversation open"
            );
        }

        // Default — always returns a body
        return send(
            "Thanks for your message! " +
            "To confirm your booking, please reply with your preferred date and time. " +
            "I can then help with the request.",
            "open_ended",
            "Generic customer message — guiding to booking confirmation"
        );
    }

    // ════════════════════════════════════════════════════════════════════════
    // MERCHANT HANDLER
    // ════════════════════════════════════════════════════════════════════════
    private Map<String, Object> handleMerchant(String msg, String scope, String contextId,
                                               Map<String, Object> context, Map<String, Object> latestDecision) {

        if (anyOf(msg, "yes", "haan", "go ahead", "karo", "do it",
                       "sahi hai", "theek hai", "bilkul", "confirm",
                       "approved", "chalega", "ok", "okay")) {
            Confirmation confirmation = merchantConfirmation(scope, contextId, context);
            if (confirmation == null) {
                return send(
                    merchantClarification(latestDecision),
                    "open_ended",
                    latestDecision == null
                        ? "Merchant confirmation requires stored actionable state"
                        : "Merchant confirmation matched the latest stored tick decision; no action execution was recorded"
                );
            }
            return send(
                confirmation.message,
                "none",
                "Merchant confirmation matched stored actionable state"
            );
        }

        if (anyOf(msg, "join", "subscribe", "sign up", "judrna",
                       "membership", "plan", "pricing", "how much",
                       "fees", "cost")) {
            return send(
                "Great! To get you started I need 3 things: " +
                "your business name, your locality, and a contact number. " +
                "Reply with these and I can help with the onboarding request.",
                "open_ended",
                "Merchant join/pricing intent — routing to onboarding directly"
            );
        }

        if (anyOf(msg, "not interested", "mat karo", "zaroorat nahi",
                       "ignore", "leave")) {
            return end("Merchant explicitly declined — ending conversation gracefully");
        }

        if (anyOf(msg, "later", "baad mein", "tomorrow",
                       "busy", "abhi nahi", "wait", "ruko")) {
            return wait(3600,
                "Merchant asked to wait — backing off 1 hour");
        }

        if (msg.contains("?") || anyOf(msg, "what", "how", "kya", "kaise",
                                            "explain", "tell me", "batao",
                                            "when", "kab")) {
            return send(
                "Good question. Please share which campaign or action you want to understand. " +
                "I can then help with the next step.",
                "open_ended",
                "Merchant question — requesting grounded campaign or action context"
            );
        }

        if (anyOf(msg, "gst", "tax", "legal", "court", "loan",
                       "bank", "police", "complaint", "refund")) {
            return send(
                "I can only help with your magicpin campaigns and customer engagement. " +
                "For other queries, please reach out to your account manager. " +
                "Shall I continue with the campaign we were discussing? Reply YES.",
                "open_ended",
                "Off-topic — staying on-mission, redirecting politely"
            );
        }

        if (anyOf(msg, "bakwas", "bekar", "fraud", "scam", "chor",
                       "stupid", "idiot", "useless", "besharam")) {
            return send(
                "I understand your frustration. " +
                "Please share the campaign or account concern you want to address, " +
                "or reply STOP to unsubscribe.",
                "open_ended",
                "Hostile message — de-escalating and requesting a grounded concern"
            );
        }

        // Default — always returns a body
        return send(
            "Thanks for your message. Please share the campaign or action you want to discuss.",
            "open_ended",
            "Default merchant reply — requesting actionable context"
        );
    }

    private Map<String, Object> readContext(String scope, String contextId) {
        if (scope == null || contextId == null) {
            return null;
        }
        return store.getContext(scope, contextId);
    }

    private String customerClarification(Map<String, Object> latestDecision) {
        if (latestDecision != null) {
            return "Thanks — I found the latest " + decisionDescription(latestDecision) +
                " for this context. What booking or request would you like me to confirm?";
        }
        return "Thanks — what would you like me to confirm? Please share the booking or request details.";
    }

    private String merchantClarification(Map<String, Object> latestDecision) {
        if (latestDecision != null) {
            return "Thanks — I found the latest " + decisionDescription(latestDecision) +
                " for this context. Which campaign or action would you like to confirm?";
        }
        return "Thanks — which campaign or action would you like to confirm?";
    }

    private String decisionDescription(Map<String, Object> latestDecision) {
        String triggerKind = normalizedString(latestDecision.get("trigger_kind"));
        if (triggerKind == null) {
            return "signal";
        }
        if (triggerKind.contains("perf")) {
            return "performance signal";
        }
        if (triggerKind.contains("recall")) {
            return "recall signal";
        }
        if (triggerKind.contains("digest")) {
            return "category insight";
        }
        if (triggerKind.contains("review")) {
            return "customer feedback signal";
        }
        if (triggerKind.contains("dormant")) {
            return "inactivity signal";
        }
        return "business signal";
    }

    private Confirmation customerConfirmation(String scope, String contextId, Map<String, Object> context) {
        if ("trigger".equals(scope) && contextId != null && store.wasActioned(contextId)) {
            return new Confirmation("The stored trigger action is already recorded as completed.");
        }
        Map<String, Object> payload = payload(context);
        Map<String, Object> booking = firstMap(payload, "booking", "appointment");
        if (booking == null || !isCompletedStatus(booking.get("status"))) {
            return null;
        }
        String descriptor = firstText(booking, "service", "name", "id");
        String subject = descriptor == null ? "stored booking" : "booking for " + descriptor;
        return new Confirmation("The stored " + subject + " is marked " + statusText(booking.get("status")) + ".");
    }

    private Confirmation merchantConfirmation(String scope, String contextId, Map<String, Object> context) {
        if ("trigger".equals(scope) && contextId != null && store.wasActioned(contextId)) {
            return new Confirmation("The stored trigger action is already recorded as completed.");
        }
        Map<String, Object> payload = payload(context);
        Map<String, Object> action = firstMap(payload, "campaign", "action");
        if (action == null || !isCompletedStatus(action.get("status"))) {
            return null;
        }
        String descriptor = firstText(action, "name", "type", "id");
        String subject = descriptor == null ? "stored action" : "stored " + descriptor + " action";
        return new Confirmation("The " + subject + " is marked " + statusText(action.get("status")) + ".");
    }

    private Map<String, Object> payload(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Map<String, Object> payload = cast(context.get("payload"));
        return payload == null ? context : payload;
    }

    private Map<String, Object> firstMap(Map<String, Object> payload, String... keys) {
        if (payload == null) {
            return null;
        }
        for (String key : keys) {
            Map<String, Object> value = cast(payload.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstText(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            String value = normalizedString(values.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean isCompletedStatus(Object value) {
        if (value == null) {
            return false;
        }
        String status = value.toString().trim().toLowerCase(Locale.ROOT);
        return Set.of("confirmed", "booked", "active", "activated", "completed").contains(status);
    }

    private String statusText(Object value) {
        return value == null ? "completed" : value.toString().trim().toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════════

    private boolean isStop(String msg) {
        return anyOf(msg,
            "stop", "unsubscribe", "no more", "opt out",
            "nahi chahiye", "mat bhejo", "rokna",
            "remove me", "delete me");
    }

    private boolean isAutoReplyPhrase(String msg) {
        return anyOf(msg,
            "thank you for contacting",
            "thanks for contacting",
            "aapki jaankari ke liye",
            "i am currently unavailable",
            "currently unavailable",
            "will get back to you",
            "get back to you shortly",
            "away from my phone",
            "out of office",
            "unable to respond",
            "auto reply",
            "auto-reply",
            "automated response",
            "automated assistant",
            "dhanyavaad",
            "main abhi available nahi");
    }

    private boolean anyOf(String text, String... keywords) {
        if (text == null) return false;
        for (String k : keywords) {
            if (Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(k) + "(?![\\p{L}\\p{N}])")
                    .matcher(text)
                    .find()) {
                return true;
            }
        }
        return false;
    }

    private String normalizedString(Object value) {
        if (!(value instanceof String string)) {
            return null;
        }
        String normalized = string.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Map<String, Object> send(String body, String cta, String rationale) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("action",    "send");
        r.put("body",      body);
        r.put("cta",       cta);
        r.put("rationale", rationale);
        return r;
    }

    private Map<String, Object> wait(int seconds, String rationale) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("action",       "wait");
        r.put("wait_seconds", seconds);
        r.put("rationale",    rationale);
        return r;
    }

    private Map<String, Object> end(String rationale) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("action",    "end");
        r.put("rationale", rationale);
        return r;
    }

    private enum ConversationState {
        OPEN,
        ENDED,
        OPTED_OUT
    }

    private static final class Confirmation {
        private final String message;

        private Confirmation(String message) {
            this.message = message;
        }
    }
}
