package com.notearray.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notearray.entity.User;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Stripe webhook endpoint (REVIEW #26, layer 3 — see STRIPE.md).
 *
 * <p>Public (no session auth) but boundary-secured: every request must carry a
 * valid {@code Stripe-Signature} header — an HMAC-SHA256 over the raw body and
 * timestamp, keyed with {@code notearray.stripe.webhook-secret}. Forged,
 * tampered, stale, or missing signatures are rejected with 400 before any user
 * row is touched.
 *
 * <p>Only downgrade-only lifecycle events are handled here; a plan can never be
 * activated or escalated through the webhook. Paid activation happens
 * exclusively in the server-verified checkout flow
 * ({@code UserResource.applyPlanSync} action "confirm"). Unknown event types and
 * users without matched Stripe identifiers are acknowledged with 200 so Stripe
 * stops retrying.
 */
@Path("/api/stripe/webhook")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StripeWebhookResource {

    private static final Logger LOG = Logger.getLogger(StripeWebhookResource.class);

    /** Maximum accepted age/skew of the signing timestamp (Stripe recommends a few minutes). */
    static final long MAX_TIMESTAMP_SKEW_SECONDS = 300;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "notearray.stripe.webhook-secret")
    Optional<String> webhookSecret;

    @POST
    @Transactional
    public Response handle(@HeaderParam("Stripe-Signature") String signatureHeader, byte[] rawBody) {
        String secret = webhookSecret != null ? webhookSecret.orElse(null) : null;
        if (secret == null || secret.isBlank()) {
            LOG.warn("Stripe webhook rejected: notearray.stripe.webhook-secret is not configured");
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("webhook not configured").build();
        }
        if (!signatureValid(rawBody, signatureHeader, secret,
                Instant.now().getEpochSecond())) {
            LOG.warn("Stripe webhook rejected: invalid or missing Stripe-Signature");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("invalid signature").build();
        }

        JsonNode event;
        try {
            event = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            LOG.warn("Stripe webhook rejected: body is not valid JSON", e);
            return Response.status(Response.Status.BAD_REQUEST).entity("invalid body").build();
        }

        String type = text(event, "type");
        JsonNode object = event.path("data").path("object");
        User user = type == null ? null : findUser(object);

        if ("invoice.payment_failed".equals(type)) {
            if (user == null) {
                LOG.warn("Stripe invoice.payment_failed did not match a known subscription/customer; ignoring");
                return Response.ok(Map.of("ignored", true)).build();
            }
            markPastDue(user);
            return Response.ok(Map.of("handled", true)).build();
        }
        if ("customer.subscription.deleted".equals(type)) {
            if (user == null) {
                LOG.warn("Stripe customer.subscription.deleted did not match a known subscription/customer; ignoring");
                return Response.ok(Map.of("ignored", true)).build();
            }
            downgradeAfterDeletion(user);
            return Response.ok(Map.of("handled", true)).build();
        }

        // Acknowledge other event types so Stripe stops retrying; intentionally unhandled.
        LOG.debugf("Stripe webhook event type %s ignored", type);
        return Response.ok(Map.of("ignored", true)).build();
    }

    /**
     * Match the event object to a note-user: a subscription object ({@code id})
     * for {@code customer.subscription.*}, or an invoice ({@code subscription}
     * field) for {@code invoice.*}. Falls back to the customer ID when the
     * subscription is unknown locally.
     */
    static User findUser(JsonNode object) {
        String subscriptionId = text(object, "subscription");
        if (subscriptionId == null || subscriptionId.isBlank()) {
            subscriptionId = text(object, "id");
        }
        User bySubscription = User.findByStripeSubscriptionId(subscriptionId);
        if (bySubscription != null) {
            return bySubscription;
        }
        return User.findByStripeCustomerId(text(object, "customer"));
    }

    /** Downgrade-only lifecycle updates: a webhook can lower billing state, never raise it. */
    static void markPastDue(User user) {
        if (user.planStatus == null || !User.PLAN_STATUS_PAST_DUE.equals(user.planStatus)) {
            user.planStatus = User.PLAN_STATUS_PAST_DUE;
            user.planUpdatedAt = Instant.now();
            LOG.infof("Marked user %s past_due via Stripe webhook", user.id);
        }
    }

    static void downgradeAfterDeletion(User user) {
        user.plan = User.DEFAULT_PLAN;
        user.pendingPlan = null;
        user.planStatus = User.PLAN_STATUS_CANCELED;
        user.stripeSubscriptionId = null;
        user.planUpdatedAt = Instant.now();
        LOG.infof("Downgraded user %s to free via Stripe webhook", user.id);
    }

    static String text(JsonNode node, String field) {
        return node != null && node.has(field) && !node.get(field).isNull()
                ? node.get(field).asText()
                : null;
    }

    /**
     * Verify a {@code Stripe-Signature} header: {@code t=<unix ts>,v1=<hex>...}
     * where every {@code v1} is an HMAC-SHA256 of {@code "<t>.<rawBody>"} keyed
     * with the webhook secret. Enforces the maximum timestamp skew and compares
     * hex digests in constant time.
     */
    static boolean signatureValid(byte[] rawBody, String header, String secret, long nowEpochSeconds) {
        if (rawBody == null || rawBody.length == 0
                || header == null || header.isBlank()
                || secret == null || secret.isBlank()) {
            return false;
        }
        Long timestamp = null;
        java.util.Set<String> signatures = new java.util.HashSet<>();
        for (String part : header.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("t=")) {
                try {
                    timestamp = Long.parseLong(trimmed.substring(2));
                } catch (NumberFormatException ignored) {
                    return false;
                }
            } else if (trimmed.startsWith("v1=")) {
                signatures.add(trimmed.substring(3).toLowerCase(Locale.ROOT));
            }
        }
        if (timestamp == null || signatures.isEmpty()
                || Math.abs(nowEpochSeconds - timestamp) > MAX_TIMESTAMP_SKEW_SECONDS) {
            return false;
        }
        String expected = hmacHex(secret, timestamp + "." + new String(rawBody, StandardCharsets.UTF_8));
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
        for (String provided : signatures) {
            if (MessageDigest.isEqual(expectedBytes, provided.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    /** Lowercase-hex HMAC-SHA256 of {@code payload}, as used by Stripe webhook signatures. */
    static String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
