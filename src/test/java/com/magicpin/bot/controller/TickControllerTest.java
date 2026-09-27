package com.magicpin.bot.controller;

import com.magicpin.bot.repository.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TickControllerTest {

    private InMemoryStore store;
    private TickController controller;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        controller = new TickController(store);
    }

    private void saveCategory(String category) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("digest", List.of(Map.of(
                "title", "Weeknight demand is steady",
                "source", "Category digest"
        )));
        payload.put("peer_stats", Map.of("avg_ctr", "4.8"));

        store.save("category:" + category, Map.of(
                "scope", "category",
                "context_id", category,
                "payload", payload
        ));
    }

    private void saveMerchant(String merchantId, String category, String merchantName, Map<String, Object> merchantPayload) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("category_slug", category);
        payload.put("identity", Map.of("name", merchantName, "owner_name", "Owner"));
        payload.putAll(merchantPayload);

        store.save("merchant:" + merchantId, Map.of(
                "scope", "merchant",
                "context_id", merchantId,
                "payload", payload
        ));
    }

    private Map<String, Object> trigger(String id, String kind, String merchantId, Integer dueCount, Integer urgency, Map<String, Object> extra) {
        Map<String, Object> payload = new HashMap<>();
        if (merchantId != null) {
            payload.put("merchant_id", merchantId);
        }
        if (dueCount != null) {
            payload.put("due_count", dueCount);
        }
        if (urgency != null) {
            payload.put("urgency", urgency);
        }
        if (extra != null) {
            payload.putAll(extra);
        }

        Map<String, Object> t = new HashMap<>();
        t.put("id", id);
        t.put("trigger_id", id);
        t.put("kind", kind);
        t.put("payload", payload);
        return t;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> actionsFor(Map<String, Object> req) {
        Map<String, Object> resp = controller.tick(req);
        return (List<Map<String, Object>>) resp.get("actions");
    }

    @Test
    void merchantSpecificTriggerIsNotBlindlyPreferredWhenBusinessImpactIsStronger() {
        saveCategory("dentists");
        saveMerchant("m_1", "dentists", "Dr Meera", Map.of(
                "signals", Map.of("trial_ending_soon", false),
                "metrics", Map.of("open_slots", 3),
                "performance", Map.of("open_slots", 3)
        ));

        Map<String, Object> generic = trigger("perf_dip", "perf_dip", null, 40, 25, Map.of("days_left", 3));
        Map<String, Object> specific = trigger("recall_due", "recall_due", "m_1", 6, 18, Map.of("days_left", 2));

        List<Map<String, Object>> actions = actionsFor(Map.of("available_triggers", List.of(generic, specific)));

        assertFalse(actions.isEmpty());
        assertEquals("perf_dip", actions.get(0).get("trigger_id"));
    }

    @Test
    void merchantSpecificTriggersNeverLeakToOtherMerchantActions() {
        saveCategory("restaurants");
        saveCategory("pharmacies");
        saveMerchant("restaurant-1", "restaurants", "Saffron Table", Map.of(
                "signals", Map.of(),
                "metrics", Map.of(),
                "performance", Map.of()
        ));
        saveMerchant("pharmacy-1", "pharmacies", "Care Plus", Map.of(
                "signals", Map.of(),
                "metrics", Map.of(),
                "performance", Map.of()
        ));

        List<Map<String, Object>> actions = actionsFor(Map.of(
                "available_triggers", List.of(
                        trigger("restaurant-perf", "perf_dip", "restaurant-1", null, 8,
                                Map.of("drop_percent", 18)),
                        trigger("pharmacy-recall", "recall_due", "pharmacy-1", 9, 8,
                                Map.of("days_left", 2))
                )
        ));

        assertEquals(2, actions.size());

        Map<String, String> selectedByMerchant = new HashMap<>();
        for (Map<String, Object> action : actions) {
            selectedByMerchant.put(
                    String.valueOf(action.get("merchant_id")),
                    String.valueOf(action.get("trigger_id"))
            );
        }

        assertEquals("restaurant-perf", selectedByMerchant.get("restaurant-1"));
        assertEquals("pharmacy-recall", selectedByMerchant.get("pharmacy-1"));
    }

    @Test
    void documentedActiveTriggerIdsAliasResolvesStoredTrigger() {
        saveMerchant("m_alias", "restaurants", "Alias Bistro", Map.of(
                "signals", Map.of(), "metrics", Map.of(), "performance", Map.of()
        ));
        store.saveContext("trigger", "stored-perf", Map.of(
                "scope", "trigger",
                "context_id", "stored-perf",
                "trigger_id", "stored-perf",
                "kind", "perf_dip",
                "payload", Map.of("merchant_id", "m_alias", "drop_percent", 11)
        ));

        List<Map<String, Object>> actions = actionsFor(Map.of(
                "active_trigger_ids", List.of("stored-perf")
        ));

        assertEquals(1, actions.size());
        assertEquals("stored-perf", actions.get(0).get("trigger_id"));
        assertEquals("m_alias", actions.get(0).get("merchant_id"));
    }

    @Test
    void noContextFallbackRetainsTheVeraActionContract() {
        List<Map<String, Object>> actions = actionsFor(Map.of());

        assertEquals(1, actions.size());
        assertEquals("vera", actions.get(0).get("send_as"));
        assertEquals("trigger:perf_dip:merchant:default", actions.get(0).get("suppression_key"));
    }

    @Test
    void urgencyAndFreshnessPreferencesAreDeterministic() {
        saveCategory("salons");
        saveMerchant("m_2", "salons", "Glow Studio", Map.of(
                "signals", Map.of("trial_ending_soon", false),
                "metrics", Map.of("open_slots", 5),
                "performance", Map.of("open_slots", 5)
        ));

        Map<String, Object> stale = trigger("festival_upcoming", "festival_upcoming", null, 12, 10, Map.of("age_days", 25));
        Map<String, Object> fresh = trigger("weather_heatwave", "weather_heatwave", null, 12, 10, Map.of("age_days", 2));

        List<Map<String, Object>> actions = actionsFor(Map.of("available_triggers", List.of(stale, fresh)));

        assertEquals("weather_heatwave", actions.get(0).get("trigger_id"));
    }

    @Test
    void repetitionPenaltyAvoidsRepeatingTheSameTrigger() {
        saveCategory("restaurants");
        saveMerchant("m_3", "restaurants", "Saffron Table", Map.of(
                "signals", Map.of("trial_ending_soon", false),
                "performance", Map.of("open_slots", 1)
        ));

        Map<String, Object> genericOne = trigger("perf_dip", "perf_dip", null, 15, 20, Map.of("days_left", 3));
        Map<String, Object> genericTwo = trigger("perf_spike", "perf_spike", null, 15, 18, Map.of("days_left", 4));

        controller.tick(Map.of("available_triggers", List.of(genericOne, genericTwo)));
        List<Map<String, Object>> actions = actionsFor(Map.of("available_triggers", List.of(genericOne, genericTwo)));

        assertEquals("perf_spike", actions.get(0).get("trigger_id"));
    }

    @Test
    void missingOptionalFieldsDoNotCrashAndStillReturnAction() {
        saveCategory("gyms");
        saveMerchant("m_4", "gyms", "Peak Fit", Map.of(
                "signals", Map.of("trial_ending_soon", false),
                "metrics", new HashMap<>(),
                "performance", new HashMap<>()
        ));

        List<Map<String, Object>> actions = actionsFor(Map.of(
                "available_triggers", List.of(
                        trigger("dormant_with_vera", "dormant_with_vera", null, null, 14, Map.of())
                )
        ));

        assertFalse(actions.isEmpty());
        assertNotNull(actions.get(0).get("body"));
    }

    @Test
    void eachSupportedCategoryGeneratesAMessageWithoutHardcodedBusinessFacts() {
        List<String> categories = List.of("restaurants", "salons", "dentists", "gyms", "pharmacies");

        for (String category : categories) {
            saveCategory(category);
            saveMerchant("m_" + category, category, "Example " + category, Map.of(
                    "signals", Map.of("trial_ending_soon", false),
                    "metrics", Map.of("open_slots", 1),
                    "performance", Map.of("open_slots", 1)
            ));

            Map<String, Object> req = Map.of("available_triggers", List.of(
                    trigger("recall_due", "recall_due", "m_" + category, 5, 18, Map.of("days_left", 2))
            ));

            List<Map<String, Object>> actions = actionsFor(req);
            assertFalse(actions.isEmpty());
            String body = String.valueOf(actions.get(0).get("body"));
            assertFalse(body.contains("₹199"));
            assertFalse(body.contains("25%"));
            assertFalse(body.contains("30%"));
            assertFalse(body.contains("weekend"));
        }
    }

    @Test
    void categoryMessagesAreSpecificAndMerchantAware() {
        saveCategory("restaurants");
        saveMerchant("m_rest", "restaurants", "Saffron Table", Map.of(
                "identity", Map.of("name", "Saffron Table", "owner_name", "Meera", "locality", "Koramangala"),
                "signals", Map.of("trial_ending_soon", false),
                "metrics", Map.of("open_slots", 2),
                "performance", Map.of("open_slots", 2)
        ));
        List<Map<String, Object>> actions = actionsFor(Map.of(
                "available_triggers", List.of(
                        trigger("perf_dip", "perf_dip", "m_rest", 18, 20, Map.of("days_left", 3, "drop_percent", 18))
                )
        ));

        String body = String.valueOf(actions.get(0).get("body"));
        assertTrue(body.contains("Saffron Table") || body.contains("Meera"));
        assertTrue(body.contains("performance is down 18%"));
        assertFalse(body.contains("over the last 3 days"));
        assertFalse(body.contains("₹199"));
        assertFalse(body.contains("weekend"));
    }

    @Test
    void categorySpecificMessagesUseRelevantSignals() {
        saveCategory("dentists");
        saveMerchant("m_dent", "dentists", "Dr Meera", Map.of(
                "identity", Map.of("name", "Dr Meera", "owner_name", "Meera", "locality", "Indiranagar"),
                "signals", Map.of("trial_ending_soon", false),
                "metrics", Map.of("open_slots", 3),
                "performance", Map.of("open_slots", 3)
        ));
        List<Map<String, Object>> actions = actionsFor(Map.of(
                "available_triggers", List.of(
                        trigger("recall_due", "recall_due", "m_dent", 7, 22, Map.of("days_left", 2, "top_item_id", "cleaning"))
                )
        ));

        String body = String.valueOf(actions.get(0).get("body"));
        assertTrue(body.contains("follow-up") || body.contains("due"));
        assertTrue(body.contains("cleaning") || body.contains("follow-up"));
        assertTrue(body.contains("2") || body.contains("days"));
        assertFalse(body.contains("₹"));
    }

    @Test
    void messagesUseActualMetricsAndDoNotInventPercentages() {
        saveCategory("pharmacies");
        saveMerchant("m_pharma", "pharmacies", "Care Plus", Map.of(
                "signals", Map.of("trial_ending_soon", false),
                "metrics", Map.of("open_slots", 0),
                "performance", Map.of("open_slots", 0)
        ));
        List<Map<String, Object>> actions = actionsFor(Map.of(
                "available_triggers", List.of(
                        trigger("recall_due", "recall_due", "m_pharma", 9, 20, Map.of("days_left", 2, "affected_customers", 9))
                )
        ));

        String body = String.valueOf(actions.get(0).get("body"));
        assertTrue(body.contains("9") || body.contains("due"));
        assertFalse(body.contains("₹199"));
        assertFalse(body.contains("25%"));
        assertFalse(body.contains("30%"));
        assertTrue(body.contains("2") || body.contains("days"));
    }

    @Test
    void messageContainsSingleClearCtaAndRationaleIsContextAware() {
        saveCategory("pharmacies");
        saveMerchant("m_5", "pharmacies", "Care Plus", Map.of(
                "signals", Map.of("trial_ending_soon", false),
                "metrics", Map.of("open_slots", 0),
                "performance", Map.of("open_slots", 0)
        ));

        List<Map<String, Object>> actions = actionsFor(Map.of(
                "available_triggers", List.of(
                        trigger("recall_due", "recall_due", "m_5", 7, 20, Map.of("days_left", 2, "affected_customers", 7))
                )
        ));

        String body = String.valueOf(actions.get(0).get("body"));
        assertTrue(body.contains("Reply YES"));
        assertEquals(1, countOccurrences(body, "Reply YES"));
        assertTrue(body.length() < 260);

        assertEquals("vera", actions.get(0).get("send_as"));
        assertEquals(
                "trigger:recall_due:merchant:m_5",
                actions.get(0).get("suppression_key")
        );

        String rationale = String.valueOf(actions.get(0).get("rationale"));
        assertTrue(rationale.contains("m_5") || rationale.contains("recall_due"));
        assertTrue(rationale.contains("follow-ups due") || rationale.contains("day(s) remaining"));
        assertFalse(rationale.contains("due_count"));
        assertFalse(rationale.contains("drop_percent"));
        assertFalse(rationale.toLowerCase().contains("important"));
    }

    @Test
    void recallWithoutDueCountDoesNotClaimCustomersAreDue() {
        saveCategory("dentists");
        saveMerchant("m_missing_due", "dentists", "Dr Meera", Map.of(
                "signals", Map.of("trial_ending_soon", false),
                "metrics", Map.of(),
                "performance", Map.of()
        ));

        String body = String.valueOf(actionsFor(Map.of(
                "available_triggers", List.of(trigger("recall_due", "recall_due", "m_missing_due", null, 10, Map.of()))
        )).get(0).get("body"));

        assertTrue(body.contains("recall follow-up signal"));
        assertFalse(body.toLowerCase().contains("customer"));
        assertFalse(body.toLowerCase().contains("patients are due"));
    }

    @Test
    void recallWithoutDaysLeftDoesNotClaimAnActiveWindow() {
        saveCategory("pharmacies");
        saveMerchant("m_missing_days", "pharmacies", "Care Plus", Map.of(
                "signals", Map.of("trial_ending_soon", false),
                "metrics", Map.of(),
                "performance", Map.of()
        ));

        String body = String.valueOf(actionsFor(Map.of(
                "available_triggers", List.of(trigger("recall_due", "recall_due", "m_missing_days", 3, 10, Map.of()))
        )).get(0).get("body"));

        assertFalse(body.toLowerCase().contains("active now"));
        assertFalse(body.toLowerCase().contains("remaining"));
        assertFalse(body.toLowerCase().contains("closes in"));
    }

    @Test
    void performanceDropDoesNotTreatDaysLeftAsHistoricalDuration() {
        saveCategory("restaurants");
        saveMerchant("m_perf", "restaurants", "Saffron Table", Map.of(
                "signals", Map.of("trial_ending_soon", false),
                "metrics", Map.of(),
                "performance", Map.of()
        ));

        String body = String.valueOf(actionsFor(Map.of(
                "available_triggers", List.of(trigger("perf_dip", "perf_dip", "m_perf", null, 10, Map.of(
                        "drop_percent", 18,
                        "days_left", 3
                )))
        )).get(0).get("body"));

        assertTrue(body.contains("performance is down 18%"));
        assertFalse(body.toLowerCase().contains("sales are down"));
        assertFalse(body.toLowerCase().contains("over the last 3 days"));
    }

    @Test
    void salonAvailabilityUsesSuppliedSlotsWithoutUnsupportedWeekClaim() {
        saveCategory("salons");
        saveMerchant("m_salon_slots", "salons", "Glow Studio", Map.of(
                "signals", Map.of("trial_ending_soon", false),
                "metrics", Map.of("open_slots", 3),
                "performance", Map.of("open_slots", 3)
        ));

        String body = String.valueOf(actionsFor(Map.of(
                "available_triggers", List.of(trigger("availability_open_slots", "availability_open_slots", "m_salon_slots", null, 10, Map.of()))
        )).get(0).get("body"));

        assertTrue(body.contains("3 open appointment slots"));
        assertFalse(body.toLowerCase().contains("this week"));
    }

    @Test
    void dentistTopItemDoesNotCreateReturnProbabilityClaim() {
        saveCategory("dentists");
        saveMerchant("m_dentist_item", "dentists", "Dr Meera", Map.of(
                "signals", Map.of("trial_ending_soon", false),
                "metrics", Map.of(),
                "performance", Map.of()
        ));

        String body = String.valueOf(actionsFor(Map.of(
                "available_triggers", List.of(trigger("recall_due", "recall_due", "m_dentist_item", 2, 10, Map.of("top_item_id", "cleaning")))
        )).get(0).get("body"));

        assertTrue(body.contains("cleaning"));
        assertFalse(body.toLowerCase().contains("most likely to return"));
    }

    @Test
    void restaurantPerformanceUsesCategoryAwareFraming() {
        saveCategory("restaurants");
        saveMerchant("m_restaurant", "restaurants", "Saffron Table", Map.of(
                "signals", Map.of("trial_ending_soon", false),
                "metrics", Map.of(),
                "performance", Map.of()
        ));

        String body = String.valueOf(actionsFor(Map.of(
                "available_triggers", List.of(trigger("perf_dip", "perf_dip", "m_restaurant", null, 10, Map.of("drop_percent", 12)))
        )).get(0).get("body"));

        assertTrue(body.contains("restaurant performance signal"));
        assertFalse(body.toLowerCase().contains("orders"));
    }

    @Test
    void gymDormancyUsesMemberFramingWithoutInventedMetrics() {
        saveCategory("gyms");
        saveMerchant("m_gym", "gyms", "Peak Fit", Map.of(
                "signals", Map.of("trial_ending_soon", false),
                "metrics", Map.of(),
                "performance", Map.of()
        ));

        String body = String.valueOf(actionsFor(Map.of(
                "available_triggers", List.of(trigger("dormant_with_vera", "dormant_with_vera", "m_gym", null, 10, Map.of()))
        )).get(0).get("body"));

        assertTrue(body.contains("member re-engagement"));
        assertFalse(body.matches(".*\\d+.*"));
    }

    @Test
    void missingLocalityDoesNotInventLocation() {
        saveCategory("restaurants");
        saveMerchant("m_no_locality", "restaurants", "Saffron Table", Map.of(
                "signals", Map.of("trial_ending_soon", false),
                "metrics", Map.of(),
                "performance", Map.of()
        ));

        String body = String.valueOf(actionsFor(Map.of(
                "available_triggers", List.of(trigger("perf_dip", "perf_dip", "m_no_locality", null, 10, Map.of()))
        )).get(0).get("body"));

        assertFalse(body.contains("Koramangala"));
        assertFalse(body.contains("Indiranagar"));
    }

    @Test
    void responseCtaFieldAndBodyContainExactlyOneReplyYesCta() {
        saveCategory("gyms");
        saveMerchant("m_cta", "gyms", "Peak Fit", Map.of(
                "signals", Map.of("trial_ending_soon", false),
                "metrics", Map.of(),
                "performance", Map.of()
        ));

        Map<String, Object> action = actionsFor(Map.of(
                "available_triggers", List.of(trigger("dormant_with_vera", "dormant_with_vera", "m_cta", null, 10, Map.of()))
        )).get(0);

        assertEquals("Reply YES", action.get("cta"));
        assertEquals(1, countOccurrences(String.valueOf(action.get("body")), "Reply YES"));
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
