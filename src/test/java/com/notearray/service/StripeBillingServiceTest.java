package com.notearray.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notearray.entity.User;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Wire-level tests for the Stripe cancellation that precedes a downgrade or an
 * account deletion. A local {@link HttpServer} stands in for Stripe so the test
 * can assert the exact request (method, path) and — crucially — that the local
 * plan is still paid while the DELETE is in flight.
 */
class StripeBillingServiceTest {

    private HttpServer server;
    private StripeBillingService billing;

    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> planDuringRequest = new AtomicReference<>();
    private final List<Integer> responderStatuses = new CopyOnWriteArrayList<>();

    private User user;

    @BeforeEach
    void setUp() throws Exception {
        user = new User();
        user.plan = "premium";
        user.planStatus = User.PLAN_STATUS_ACTIVE;
        user.stripeSubscriptionId = "sub_test_123";

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/subscriptions/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            planDuringRequest.set(user.plan);

            int status = responderStatuses.isEmpty() ? 200 : responderStatuses.get(0);
            byte[] body = (status == 200
                    ? "{\"id\":\"sub_test_123\",\"status\":\"canceled\"}"
                    : "{\"error\":{\"message\":\"boom\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        billing = new StripeBillingService();
        billing.objectMapper = new ObjectMapper();
        billing.stripeApiKey = Optional.of("sk_test_fake");
        billing.stripeApiBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsDeleteToStripeWhileThePlanIsStillPaid() {
        assertTrue(billing.awaitSubscriptionCancellation(user));

        assertEquals("DELETE", method.get());
        assertEquals("/v1/subscriptions/sub_test_123", path.get());
        // The account must not be shown as free until Stripe confirmed the cancel.
        assertEquals("premium", planDuringRequest.get());

        assertEquals("free", user.plan);
        assertEquals(User.PLAN_STATUS_CANCELED, user.planStatus);
        assertNull(user.stripeSubscriptionId);
    }

    @Test
    void failedCancellationLeavesThePaidPlanIntact() {
        responderStatuses.add(500);

        assertFalse(billing.awaitSubscriptionCancellation(user));

        assertEquals("DELETE", method.get());
        assertEquals("premium", user.plan);
        assertEquals("sub_test_123", user.stripeSubscriptionId);
    }

    @Test
    void subscriptionCannotBeSilentlyDroppedWithoutAnApiKey() {
        billing.stripeApiKey = Optional.empty();

        assertFalse(billing.awaitSubscriptionCancellation(user));

        assertNull(method.get(), "no Stripe request may be attempted without an API key");
        assertEquals("premium", user.plan);
        assertEquals("sub_test_123", user.stripeSubscriptionId);
    }

    @Test
    void freeUserWithoutSubscriptionNeedsNoStripeCall() {
        user.plan = "free";
        user.stripeSubscriptionId = null;

        assertTrue(billing.awaitSubscriptionCancellation(user));

        assertNull(method.get());
        assertEquals("free", user.plan);
    }
}
