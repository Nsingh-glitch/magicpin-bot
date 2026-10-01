# MAGICPIN BOT - QA & REGRESSION REPORT

**Execution date:** 2026-10-02  
**Test type:** Production API QA, source review, deterministic-flow verification, and repository regression testing  
**Scope:** Non-destructive application-level requests only. No DoS, credential, destructive, or persistence attacks.

## Executive Result

- Production probes: **65 executed, 63 passed, 2 failed** under the documented repository contract.
- Repository regression suite: **66 executed, 66 passed, 0 failed, 0 skipped**.
- Combined pass rate is not reported because production cases and automated tests overlap in behavior and use different evidence/expectation models.
- Automated pass rate: `66 / 66 x 100 = 100%`.
- Two confirmed findings are listed below. No application source was modified for this QA exercise.

## Environment

- Production URL: https://magicpin-bot-76xf.onrender.com/
- Backend: Spring Boot REST application
- Java: 17 (repository `pom.xml` and Dockerfile)
- Spring Boot: 3.2.5
- Deployed health response: `status=ok`; observed `contexts_loaded=32` at first probe
- Metadata response: version `1.0`; deterministic composer with optional Gemini enrichment

## Production API Results

| Area | Tests | Passed | Failed | Notes |
|---|---:|---:|---:|---|
| API Reliability | 5 | 5 | 0 | `/v1/healthz`, `/v1/health`, `/v1/metadata`, root page, unknown route behaved as expected; unknown route returned 404. |
| Input Validation | 16 | 15 | 1 | Malformed JSON and missing content type were controlled; malformed `payload` object was accepted with 200. |
| Conversation Flow | 10 | 10 | 0 | Customer, merchant, ambiguous, clarification, multi-turn auto-reply, long, random, and changed-intent cases returned structured responses. |
| State Management | 6 | 6 | 0 | OPEN, ENDED, OPTED_OUT terminal behavior and cross-conversation isolation observed. State is keyed by conversation ID. |
| STOP/Opt-out Handling | 10 | 9 | 1 | STOP variants, embedded STOP, unsubscribe, `no more`, Hindi, and `remove me` passed; natural-language opt-out failed. |
| Decision/Scoring | 4 | 4 | 0 | Merchant-specific matching, competing triggers, repetition behavior, and unknown-category fallback were deterministic. Numeric score is not exposed by the API. |
| Tick/Trigger Flow | 5 | 5 | 0 | Explicit triggers, active-trigger alias behavior, fallback behavior, null request, and repeated selection were exercised. |
| Knowledge Retrieval | 3 | 3 | 0 | Restaurant/dentist grounded wording and unknown-category generic wording observed; no unsupported business facts appeared. Retrieval is not directly exposed as an endpoint. |
| AI Response Safety | 4 | 4 | 0 | HTML/script, SQL-like, prompt-injection, and unsupported-business-detail probes did not produce injected claims. |
| Error Handling | 8 | 8 | 0 | 400 malformed/empty-body, 415 missing content type, and 405 wrong-method behavior observed where applicable. |
| Regression Tests | 66 | 66 | 0 | Maven test suite; 0 skipped. |

Category totals overlap by design and must not be added together.

## Endpoint Checks

| Endpoint/case | Actual result |
|---|---|
| `GET /v1/healthz` | 200 JSON: `status=ok`, uptime, context count |
| `GET /v1/health` | 200 and consistent health fields |
| `GET /v1/metadata` | 200 with name/version/model/approach |
| `POST /v1/context` valid | 200 `{accepted:true}` |
| `POST /v1/tick` valid | 200 `{actions:[...]}` with `send_as`, CTA, suppression key, rationale |
| `POST /v1/reply` valid | 200 structured action response |
| Malformed JSON | 400 on context, tick, reply |
| JSON without content type | 415 on context, reply |
| `GET /v1/reply` | 405 with `Allow: POST` |
| Empty context body | 400 |
| Null tick body | 200 fallback actions |

## Conversation, State, and Safety Observations

- Normal customer question returned an open-ended service/pricing/timing clarification.
- Merchant pricing/join intent requested business name, locality, and contact number; it did not invent pricing.
- Customer confirmation without stored booking state stayed open and requested details.
- A first auto-reply signal probed once; the second ended the conversation; later messages stayed terminal.
- STOP, `stop`, `Stop`, `STOP PLEASE`, `please stop`, embedded STOP, `no more`, `nahi chahiye`, `remove me`, and `unsubscribe` ended safely.
- `I do not want this anymore` returned a normal merchant message and did not opt out.
- A terminal conversation remained terminal even when a different context ID was supplied. A different conversation using the same context remained open, so no cross-conversation state leak was observed.
- Prompt injection text did not cause an activation claim or expose internal instructions. Because it contained the token `ignore`, it ended through the merchant decline branch; this is safe but may be an intent-classification false positive.

## Tick, Scoring, and Grounding

An isolated production run saved two fresh merchant contexts and supplied two targeted triggers:

| Context | Input trigger | Actual selection | Reply continuity |
|---|---|---|---|
| `qa_iso_ctx_a_20261002` | targeted `perf_dip`, 17% | `perf_dip` for the matching merchant | latest performance signal |
| `qa_iso_ctx_b_20261002` | targeted `recall_due`, 6 due, 2 days | `recall_due` for the matching merchant | latest recall signal |

A second competing tick selected the previously selected trigger again for the isolated merchant, consistent with the source repetition-penalty threshold and the observed score ordering. The API exposes the selected trigger and rationale, but not the internal numeric score; numeric score verification therefore required source inspection and cannot be claimed as an externally observable metric.

Relevant grounding results:

- Known categories used supplied facts such as merchant name, 17% performance change, six due follow-ups, and two days remaining.
- An unknown category produced generic performance wording and did not invent prices, discounts, locations, availability, or business-specific facts.
- No observed response contradicted the deterministic selected trigger.
- Gemini enablement cannot be confirmed from the public API; the observed output was consistent with deterministic fallback/composition.

## Bugs and Findings

### 1. High - Natural-language opt-out is not honored

- **Test case:** Requested STOP/opt-out safety variation.
- **Input:** `{"conversation_id":"qa_stop_natural","from_role":"merchant","message":"I do not want this anymore"}`
- **Expected:** End the conversation and mark it opted out, consistent with the requested opt-out behavior.
- **Actual:** HTTP 200 with `action=send`, body `Thanks for your message. Please share the campaign or action you want to discuss.`
- **Reproduction:** POST the input above to `/v1/reply`; repeat with a new conversation ID to avoid terminal-state effects.
- **Likely component:** `ReplyController.isStop` phrase matching.
- **Suggested fix:** Add explicit natural-language opt-out phrases and regression tests, while preserving word-boundary matching and STOP priority.

### 2. Medium - `/v1/context` accepts a malformed payload shape

- **Test case:** Invalid values/types.
- **Input:** `{"scope":"merchant","context_id":"qa_iso_bad_payload_20261002","payload":"not-an-object"}`
- **Expected:** Controlled 400 for a wrong request shape, consistent with the README API contract.
- **Actual:** HTTP 200 with `{accepted:true}`; later tick processing silently skipped the malformed merchant payload.
- **Reproduction:** POST the input above to `/v1/context`, then inspect the 200 response and submit a tick for the context.
- **Likely component:** `ContextController` validates only non-empty `scope` and `context_id`; `TickController` ignores non-map payloads.
- **Suggested fix:** Validate that optional structured fields such as `payload` are JSON objects before storing them, or explicitly document and test permissive storage semantics.

## Coverage Gaps and Risks

- No public endpoint exposes selection counts, internal numeric scores, latest decisions, or retrieved knowledge; those behaviors were inferred from action output plus source/tests.
- Production state is in memory and was not isolated: the service reported 32 loaded contexts and existing contexts affected broad fallback ticks. Fresh unique IDs were used for isolated checks.
- Existing tests are predominantly controller/unit tests with MockMvc/in-process stores; they do not constitute a full production deployment test.
- There is no automated production contract test in the repository.
- The deployed metadata name is `Sumit Bot`, while the project/report identity is Magicpin Bot; this is a branding/metadata consistency gap, not counted as a functional failure.
- No authentication or authorization was tested because the repository explicitly treats it as out of scope.

## Regression Test Details

Command: `mvn test`  
Result: **BUILD SUCCESS**  
Tests: **66 run, 66 passed, 0 failures, 0 errors, 0 skipped**  
Reported Maven execution time: **6.550 seconds**

Observed test classes included reply behavior, tick selection, enrichment, knowledge-safe Gemini validation, health, context validation, and endpoint aliases.

---

# LINKEDIN-READY SUMMARY

## MAGICPIN BOT - QA & REGRESSION REPORT

**Environment**  
Production URL: https://magicpin-bot-76xf.onrender.com/  
Backend: Spring Boot REST application  
Java: 17  
Spring Boot: 3.2.5

**Tests Executed:** 131 total evidence items  
**Tests Passed:** 129  
**Tests Failed:** 2  
**Automated Regression:** 66/66 passed, 0 skipped  
**Production Probes:** 63/65 passed  
**Pass Rate:** Not calculated overall because production probes and automated tests overlap; automated formula: `66 / 66 x 100 = 100%`.

**Key areas tested**  
✓ API  
✓ Conversation flow  
✓ State management  
✓ STOP/opt-out  
✓ Deterministic decision layer  
✓ Knowledge retrieval  
✓ AI response safety  
✓ Error handling  
✓ Regression tests

**Critical Issues:** 0  
**High Issues:** 1  
**Medium Issues:** 1  
**Low Issues:** 0

**Overall**  
The deployed service was reachable and its core API, deterministic tick flow, context isolation, terminal conversation states, grounding safeguards, and existing regression suite behaved as expected. Two actionable gaps remain: one requested natural-language opt-out was not recognized, and malformed structured context payloads were accepted instead of rejected.

**5 strongest test results**

1. `66/66` repository regression tests passed with zero skipped.
2. Production malformed JSON returned controlled 400 responses; missing JSON content type returned 415.
3. Isolated merchant contexts preserved separate `perf_dip` and `recall_due` decisions through `/v1/reply`.
4. STOP variants and embedded STOP terminated without triggering business actions.
5. Unknown-category and adversarial inputs produced generic or grounded responses without invented prices, locations, availability, or activation claims.

**3 most important weaknesses/gaps**

1. Natural-language opt-out wording is narrower than the requested safety behavior.
2. Structured context payload types are not validated before storage.
3. Production observability does not expose scores, selection counts, retrieval evidence, or Gemini enablement.

**3 recommended next improvements**

1. Add explicit natural-language opt-out patterns and production-style regression cases.
2. Add request DTO/schema validation for context payloads and return 400 for invalid shapes.
3. Add a non-sensitive diagnostics or contract-test surface for decision scores, retrieval evidence, and deployment configuration state.