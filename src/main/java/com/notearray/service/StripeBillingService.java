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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Coordinates subscription cancellation with Stripe before deleting a paid
 * ('pro' or 'premium') user account.
 */
@ApplicationScoped
public class StripeBillingService {

    private static final Logger LOG = Logger.getLogger(StripeBillingService.class);
    private static final Duration STRIPE_TIMEOUT = Duration.ofSeconds(15);

    @ConfigProperty(name = "notearray.stripe.api-key")
    Optional<String> stripeApiKey;

    @ConfigProperty(name = "notearray.stripe.api-base-url", defaultValue = "https://api.stripe.com")
    String stripeApiBaseUrl;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Cancel the user's recurring Stripe subscription (if they are on a paid plan
     * or have an active {@code stripeSubscriptionId}) and await confirmation from
     * Stripe before allowing account deletion.
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

        user.planStatus = User.PLAN_STATUS_PENDING_CANCELLATION;
        user.planUpdatedAt = Instant.now();

        String apiKey = stripeApiKey != null ? stripeApiKey.orElse(null) : null;
        if (hasSubscriptionId && apiKey != null && !apiKey.isBlank()) {
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

    boolean cancelStripeSubscriptionHttp(String apiKey, String subscriptionId) {
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
