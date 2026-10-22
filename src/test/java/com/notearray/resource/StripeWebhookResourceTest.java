package com.notearray.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.notearray.entity.User;
import com.notearray.resource.UserResource.PlanSyncRequest;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Stripe webhook layer (REVIEW #26, layer 3): signature verification boundary,
 * downgrade-only lifecycle handlers, and removal of client-callable billing
 * events from the sync endpoint.
 */
class StripeWebhookResourceTest {

    private static final String SECRET = "whsec_test_webhook_secret";
    private static final String BODY = "{\"id\":\"evt_1\",\"type\":\"invoice.payment_failed\"}";
    private static final long NOW = 1_700_000_000L;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.plan = "pro";
        user.planStatus = User.PLAN_STATUS_ACTIVE;
        user.pendingPlan = "premium";
        user.stripeCustomerId = "cus_123";
        user.stripeSubscriptionId = "sub_123";
    }

    /**
     * Hand-build a Stripe-style signature header for BODY with a given secret.
     */
    private String sign(String secret, String body, long timestamp) {
        return "t=" + timestamp + ",v1="
                + StripeWebhookResource.hmacHex(secret, timestamp + "." + body);
    }

    private boolean valid(String header, String body, long now) {
        return StripeWebhookResource.signatureValid(
                body.getBytes(StandardCharsets.UTF_8), header, SECRET, now);
    }

    // --- Signature verification boundary ------------------------------------

    @Test
    void acceptsCorrectlySignedRequest() {
        long timestamp = NOW - 10;
        assertTrue(valid(sign(SECRET, BODY, timestamp), BODY, NOW));
    }

    @Test
    void acceptsTheRecentTimestampBoundary() {
        // Exactly MAX_TIMESTAMP_SKEW_SECONDS old must still pass.
        long timestamp = NOW - StripeWebhookResource.MAX_TIMESTAMP_SKEW_SECONDS;
        assertTrue(valid(sign(SECRET, BODY, timestamp), BODY, NOW));
    }

    @Test
    void rejectsDemandReplayBeyondTheTimestampWindow() {
        long timestamp = NOW - StripeWebhookResource.MAX_TIMESTAMP_SKEW_SECONDS - 1;
        assertFalse(valid(sign(SECRET, BODY, timestamp), BODY, NOW));
    }

    @Test
    void rejectsFutureDatedTimestamps() {
        long timestamp = NOW + StripeWebhookResource.MAX_TIMESTAMP_SKEW_SECONDS + 1;
        assertFalse(valid(sign(SECRET, BODY, timestamp), BODY, NOW));
    }

    @Test
    void rejectsWrongSecret() {
        assertFalse(valid(sign("whsec_attacker_secret", BODY, NOW), BODY, NOW));
    }

    @Test
    void rejectsTamperedBody() {
        String tampered = BODY.replace("payment_failed", "payment_succeeded");
        assertFalse(valid(sign(SECRET, BODY, NOW), tampered, NOW));
    }

    @Test
    void rejectsMissingHeaderOrMisformedHeaderParts() {
        String ownSignature = sign(SECRET, BODY, NOW);
        assertFalse(valid(null, BODY, NOW));
        assertFalse(valid("", BODY, NOW));
        assertFalse(valid("   ", BODY, NOW));
        // No timestamp
        assertFalse(valid("v1=" + ownSignature.split("v1=")[1], BODY, NOW));
        // No signature
        assertFalse(valid("t=" + NOW, BODY, NOW));
        // Malformed timestamp
        assertFalse(valid("t=not-a-number,v1=abcd", BODY, NOW));
        // Empty payload body
        assertFalse(valid(ownSignature, "", NOW));
    }

    @Test
    void hexComparisonIsCaseInsensitiveAndExtraSignaturesAreIgnoredSafely() {
        long timestamp = NOW - 1;
        String signature = StripeWebhookResource.hmacHex(SECRET, timestamp + "." + BODY);
        // The hex digest comparison is case-insensitive (parser lowercases both sides).
        assertTrue(valid("t=" + timestamp + ",v1=" + signature.toUpperCase(), BODY, NOW));
        // Real Stripe headers may carry several v1 values; one valid must be enough.
        assertTrue(valid("t=" + timestamp + ",v1=deadbeef,v1=" + signature, BODY, NOW));
    }

    @Test
    void hmacMatchesAnIndependentRfc4231Vector() {
        // RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?".
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                StripeWebhookResource.hmacHex("Jefe".repeat(1),
                        "what do ya want for nothing?"));
    }

    // --- Lifecycle handlers are downgrade-only -------------------------------

    @Test
    void markPastDueKeepsThePlanAndOnlyLowersTheStatus() {
        Instant before = user.planUpdatedAt;
        user.stripeSubscriptionId = "sub_123";

        StripeWebhookResource.markPastDue(user);

        assertEquals("pro", user.plan);
        assertEquals(User.PLAN_STATUS_PAST_DUE, user.planStatus);
        assertEquals("sub_123", user.stripeSubscriptionId);
        assertTrue(!user.planUpdatedAt.equals(before), "planUpdatedAt must move");
    }

    @Test
    void markPastDueIsIdempotentAndKeepsTheExistingTimestamp() {
        StripeWebhookResource.markPastDue(user);
        Instant afterFirst = user.planUpdatedAt;
        StripeWebhookResource.markPastDue(user);
        assertEquals(afterFirst, user.planUpdatedAt);
        assertEquals(User.PLAN_STATUS_PAST_DUE, user.planStatus);
    }

    @Test
    void downgradeAfterDeletionDropsToFreeAndClearsSubscription() {
        StripeWebhookResource.downgradeAfterDeletion(user);

        assertEquals("free", user.plan);
        assertNull(user.pendingPlan);
        assertNull(user.stripeSubscriptionId);
        assertEquals(User.PLAN_STATUS_CANCELED, user.planStatus);
        // The customer ID survives: it identifies real payments for later verification.
        assertEquals("cus_123", user.stripeCustomerId);
    }

    @Test
    void webhookEventTextIsReadSafely() throws Exception {
        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree("{\"subscription\":\"sub_1\",\"customer\":null}");
        assertEquals("sub_1", StripeWebhookResource.text(node, "subscription"));
        // null-valued fields and missing nodes must yield null, not throw.
        assertNull(StripeWebhookResource.text(node, "customer"));
        assertNull(StripeWebhookResource.text(node, "not_there"));
        assertNull(StripeWebhookResource.text(null, "id"));
    }

    // --- Client can no longer report billing events ---------------------------

    @Test
    void pastDueAndSubscriptionDeletedAreNoLongerClientActions() {
        Response pastDue = UserResource.applyPlanSync(user, new PlanSyncRequest("past_due", null), null);
        assertEquals(400, pastDue.getStatus());

        Response deleted = UserResource.applyPlanSync(
                user, new PlanSyncRequest("subscription_deleted", null), null);
        assertEquals(400, deleted.getStatus());

        // The register must be untouched: no client request was able to change billing state.
        assertEquals("pro", user.plan);
        assertEquals(User.PLAN_STATUS_ACTIVE, user.planStatus);
    }

    @Test
    void cancelAndConfirmRemainValidActions() {
        Response cancel = UserResource.applyPlanSync(user, new PlanSyncRequest("cancel", null), null);
        assertEquals(200, cancel.getStatus());
        assertNull(user.pendingPlan);
        assertEquals(User.PLAN_STATUS_ACTIVE, user.planStatus);

        // confirm still parses, but demands the opaque checkout session (Layer 2).
        Response confirmWithoutSession = UserResource.applyPlanSync(
                user, new PlanSyncRequest("confirm", null), null);
        assertEquals(400, confirmWithoutSession.getStatus());
    }
}
