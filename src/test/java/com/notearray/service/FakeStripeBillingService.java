package com.notearray.service;

import com.notearray.entity.User;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Test double for {@link StripeBillingService} that never touches the network.
 * It records the subscription ids passed to the cancellation call and the plan
 * the user was on at that exact moment, so tests can assert that the local plan
 * is still paid while Stripe is being asked to cancel.
 */
public class FakeStripeBillingService extends StripeBillingService {

    /** Result returned by the cancellation call. */
    private final boolean cancellationConfirmed;

    /** Optional user whose plan is sampled when the cancellation call runs. */
    private User user;

    /** Subscription ids passed to the cancellation call, in order. */
    public final List<String> cancelledSubscriptionIds = new ArrayList<>();

    /** {@code user.plan} sampled at the start of each cancellation call. */
    public final List<String> plansDuringCancellation = new ArrayList<>();

    public FakeStripeBillingService(boolean cancellationConfirmed) {
        this.cancellationConfirmed = cancellationConfirmed;
        // A subscription id is only cancellable when an API key is configured.
        this.stripeApiKey = Optional.of("sk_test_fake");
    }

    /** Sample this user's plan when the cancellation call runs. */
    public FakeStripeBillingService samplingPlanOf(User user) {
        this.user = user;
        return this;
    }

    @Override
    protected boolean cancelStripeSubscriptionHttp(String apiKey, String subscriptionId) {
        cancelledSubscriptionIds.add(subscriptionId);
        plansDuringCancellation.add(user == null ? null : user.plan);
        return cancellationConfirmed;
    }
}
