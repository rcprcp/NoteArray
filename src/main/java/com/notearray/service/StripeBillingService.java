package com.notearray.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notearray.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Coordinates subscription cancellation with Stripe before deleting a paid
 * ('pro' or 'premium') user account or downgrading that account to the free plan.
 */
@ApplicationScoped
public class StripeBillingService {

    private static final Logger LOG = Logger.getLogger(StripeBillingService.class);
    private static final Duration STRIPE_TIMEOUT = Duration.ofSeconds(15);

    @ConfigProperty(name = "notearray.stripe.api-key")
    Optional<String> stripeApiKey;

    @ConfigProperty(name = "notearray.stripe.api-base-url", defaultValue = "https://api.stripe.com")
    String stripeApiBaseUrl;

    /**
     * Price ID of the Pro subscription. The price → plan mapping is server-side
     * config so a client can never name a plan (REVIEW finding #26).
     */
    @ConfigProperty(name = "notearray.stripe.pro-price-id")
    Optional<String> proPriceId;

    /** Price ID of the Premium subscription. */
    @ConfigProperty(name = "notearray.stripe.premium-price-id")
    Optional<String> premiumPriceId;

    /** Stripe checkout session IDs (cs_test_... / cs_live_...). Used unauthenticated API-side only. */
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^cs_(test|live)_[A-Za-z0-9]{10,}$");

    /** Plan tier + Stripe identifiers resolved server-side from a verified checkout. */
    public record VerifiedCheckout(String plan, String stripeCustomerId, String stripeSubscriptionId) {}

    @Inject
    ObjectMapper objectMapper;

    /**
     * Cancel the user's recurring Stripe subscription (if they are on a paid plan
     * or have an active {@code stripeSubscriptionId}) and await confirmation from
     * Stripe before allowing an account deletion or a downgrade to the free plan.
     *
     * <p>When a subscription id is present the cancellation is mandatory: without
     * a configured API key, or when Stripe does not confirm the cancellation,
     * this returns {@code false} and the caller must not commit its free-plan /
     * deletion change — otherwise the customer would keep being charged.
     *
     * @return {@code true} when the subscription is confirmed canceled (or no
     *         active paid subscription exists in Stripe), {@code false} if Stripe
     *         rejected or failed to confirm the cancellation.
     */
    public boolean awaitSubscriptionCancellation(User user) {
        if (user == null) {
            return false;
        }
        String plan = user.plan == null ? "free" : user.plan.trim().toLowerCase();
        boolean isPaidPlan = !"free".equals(plan);
        boolean hasSubscriptionId = user.stripeSubscriptionId != null && !user.stripeSubscriptionId.isBlank();

        if (!isPaidPlan && !hasSubscriptionId) {
            return true;
        }

        String apiKey = stripeApiKey != null ? stripeApiKey.orElse(null) : null;
        if (hasSubscriptionId && (apiKey == null || apiKey.isBlank())) {
            LOG.warn("Cannot cancel the Stripe subscription: notearray.stripe.api-key is not configured");
            return false;
        }

        user.planStatus = User.PLAN_STATUS_PENDING_CANCELLATION;
        user.planUpdatedAt = Instant.now();

        if (hasSubscriptionId) {
            boolean confirmed = cancelStripeSubscriptionHttp(apiKey.trim(), user.stripeSubscriptionId.trim());
            if (!confirmed) {
                return false;
            }
        }

        user.plan = "free";
        user.pendingPlan = null;
        user.stripeSubscriptionId = null;
        user.planStatus = User.PLAN_STATUS_CANCELED;
        user.planUpdatedAt = Instant.now();
        return true;
    }

    /**
     * Verify a Stripe Checkout Session server-side (REVIEW finding #26): the
     * client may only supply the opaque session ID; everything else (plan,
     * customer, subscription) is read from Stripe using the server API key.
     *
     * @return the verified checkout, or {@code null} when the session ID is
     *         malformed, missing a completed+paid status, unsubscribed, its
     *         price is not mapped to a plan, or Stripe is unreachable/misconfigured.
     */
    public VerifiedCheckout verifyCheckoutSession(String checkoutSessionId) {
        if (checkoutSessionId == null || checkoutSessionId.isBlank()) {
            return null;
        }
        String sessionId = checkoutSessionId.trim();
        if (!SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            LOG.warnf("Rejected malformed Stripe checkout session id (must match cs_test/live_*): %d chars",
                    sessionId.length());
            return null;
        }
        String apiKey = stripeApiKey != null ? stripeApiKey.orElse(null) : null;
        if (apiKey == null || apiKey.isBlank()) {
            LOG.warn("notearray.stripe.api-key is not configured; cannot verify checkout session");
            return null;
        }

        String sessionBody = stripeHttpGet(apiKey, "/v1/checkout/sessions/" + sessionId);
        if (sessionBody == null) {
            return null;
        }
        try {
            JsonNode session = objectMapper.readTree(sessionBody);
            String status = text(session, "status");
            String paymentStatus = text(session, "payment_status");
            if (!"complete".equals(status) || !"paid".equals(paymentStatus)) {
                LOG.warnf("Stripe checkout session %s is not complete/paid (status=%s, payment_status=%s)",
                        sessionId, status, paymentStatus);
                return null;
            }
            String subscriptionId = text(session, "subscription");
            if (subscriptionId == null || subscriptionId.isBlank()) {
                LOG.warnf("Stripe checkout session %s has no subscription", sessionId);
                return null;
            }

            String subBody = stripeHttpGet(apiKey,
                    "/v1/subscriptions/" + subscriptionId + "?expand[]=items.data.price");
            if (subBody == null) {
                return null;
            }
            JsonNode subscription = objectMapper.readTree(subBody);
            JsonNode items = subscription.path("items").path("data");
            if (!items.isArray() || items.isEmpty()) {
                LOG.warnf("Stripe subscription %s has no items", subscriptionId);
                return null;
            }
            String plan = planForPrice(
                    text(items.get(0).path("price"), "id"),
                    proPriceId != null ? proPriceId.orElse(null) : null,
                    premiumPriceId != null ? premiumPriceId.orElse(null) : null);
            if (plan == null) {
                LOG.warnf("Stripe subscription %s price is not mapped to a plan "
                        + "(configure notearray.stripe.pro-price-id / premium-price-id)", subscriptionId);
                return null;
            }

            String customerId = text(session, "customer");
            if (customerId == null || customerId.isBlank()) {
                customerId = text(subscription, "customer");
            }
            return new VerifiedCheckout(plan, customerId, subscriptionId);
        } catch (IOException e) {
            LOG.warn("Failed to read Stripe verification response", e);
            return null;
        }
    }

    /** Map a Stripe price ID to a plan tier, or {@code null} when unmapped/unknown. */
    public static String planForPrice(String priceId, String proPriceId, String premiumPriceId) {
        if (priceId == null || priceId.isBlank()) {
            return null;
        }
        if (priceId.equals(proPriceId)) {
            return "pro";
        }
        if (priceId.equals(premiumPriceId)) {
            return "premium";
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    /** GET {@code path} from the Stripe API with the given secret key; body or null. */
    String stripeHttpGet(String apiKey, String path) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(STRIPE_TIMEOUT)
                    .build();
            String basicAuth = Base64.getEncoder()
                    .encodeToString((apiKey + ":").getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(stripeApiBaseUrl + path))
                    .timeout(STRIPE_TIMEOUT)
                    .header("Authorization", "Basic " + basicAuth)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warnf("Stripe GET %s returned HTTP %d", path, response.statusCode());
                return null;
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warnf(e, "Stripe GET %s failed", path);
            return null;
        }
    }

    protected boolean cancelStripeSubscriptionHttp(String apiKey, String subscriptionId) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(STRIPE_TIMEOUT)
                    .build();
            String basicAuth = Base64.getEncoder()
                    .encodeToString((apiKey + ":").getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(stripeApiBaseUrl + "/v1/subscriptions/" + subscriptionId))
                    .timeout(STRIPE_TIMEOUT)
                    .header("Authorization", "Basic " + basicAuth)
                    .DELETE()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                // Subscription is already deleted on Stripe's side
                return true;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warnf("Stripe subscription cancellation returned HTTP %d", response.statusCode());
                return false;
            }
            if (objectMapper != null && response.body() != null && !response.body().isBlank()) {
                JsonNode node = objectMapper.readTree(response.body());
                String status = node.has("status") ? node.get("status").asText() : "";
                return "canceled".equalsIgnoreCase(status);
            }
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Failed awaiting Stripe subscription cancellation confirmation", e);
            return false;
        }
    }
}
