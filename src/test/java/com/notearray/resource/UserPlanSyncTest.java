package com.notearray.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.notearray.entity.User;
import com.notearray.resource.UserResource.PlanCheckoutRequest;
import com.notearray.resource.UserResource.PlanCheckoutResponse;
import com.notearray.resource.UserResource.PlanSyncRequest;
import com.notearray.service.StripeBillingService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserPlanSyncTest {

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.plan = "free";
        user.planStatus = User.PLAN_STATUS_ACTIVE;
    }

    @Test
    void checkoutPaidPlanSetsPendingCheckoutStateAndReturnsStripeUrl() {
        Response res = UserResource.applyPlanCheckout(
                user,
                new PlanCheckoutRequest("pro"),
                "https://checkout.stripe.com/pay/pro_123",
                "https://checkout.stripe.com/pay/premium_456");

        assertEquals(200, res.getStatus());
        PlanCheckoutResponse body = (PlanCheckoutResponse) res.getEntity();
        assertEquals("free", user.plan);
        assertEquals("pro", user.pendingPlan);
        assertEquals(User.PLAN_STATUS_PENDING_CHECKOUT, user.planStatus);
        assertEquals("https://checkout.stripe.com/pay/pro_123", body.checkoutUrl());
        assertNotNull(user.planUpdatedAt);
    }

    @Test
    void syncConfirmActivatesPendingPaidPlanAndStoresStripeIds() {
        UserResource.applyPlanCheckout(user, new PlanCheckoutRequest("premium"), null, null);

        Response res = UserResource.applyPlanSync(
                user,
                new PlanSyncRequest("confirm", null, "cus_abc123", "sub_xyz789"));

        assertEquals(200, res.getStatus());
        assertEquals("premium", user.plan);
        assertNull(user.pendingPlan);
        assertEquals(User.PLAN_STATUS_ACTIVE, user.planStatus);
        assertEquals("cus_abc123", user.stripeCustomerId);
        assertEquals("sub_xyz789", user.stripeSubscriptionId);
    }

    @Test
    void syncCancelRevertsPendingPlanAndKeepsCurrentPlanActive() {
        user.plan = "pro";
        UserResource.applyPlanCheckout(user, new PlanCheckoutRequest("premium"), null, null);

        Response res = UserResource.applyPlanSync(user, new PlanSyncRequest("cancel", null, null, null));

        assertEquals(200, res.getStatus());
        assertEquals("pro", user.plan);
        assertNull(user.pendingPlan);
        assertEquals(User.PLAN_STATUS_ACTIVE, user.planStatus);
    }

    @Test
    void downgradeToFreeImmediatelyActivatesFreeAndClearsSubscription() {
        user.plan = "premium";
        user.stripeSubscriptionId = "sub_xyz789";

        Response res = UserResource.applyPlanCheckout(user, new PlanCheckoutRequest("free"), null, null);

        assertEquals(200, res.getStatus());
        assertEquals("free", user.plan);
        assertNull(user.pendingPlan);
        assertNull(user.stripeSubscriptionId);
        assertEquals(User.PLAN_STATUS_ACTIVE, user.planStatus);
    }

    @Test
    void rejectsInvalidPlanOrSyncAction() {
        Response badPlan = UserResource.applyPlanCheckout(user, new PlanCheckoutRequest("enterprise"), null, null);
        assertEquals(400, badPlan.getStatus());

        Response badAction = UserResource.applyPlanSync(user, new PlanSyncRequest("unknown", null, null, null));
        assertEquals(400, badAction.getStatus());
    }

    @Test
    void deleteAccountAwaitsStripeCancellationForPaidPlansAndBlocksPendingUpgrades() {
        StripeBillingService billing = new StripeBillingService();
        assertNull(UserResource.cancelSubscriptionAndValidateDeletion(user, billing));

        // Free user who is in the middle of upgrading to Pro (pending_checkout) is blocked until resolved
        UserResource.applyPlanCheckout(user, new PlanCheckoutRequest("pro"), null, null);
        Response pendingRes = UserResource.cancelSubscriptionAndValidateDeletion(user, billing);
        assertNotNull(pendingRes);
        assertEquals(400, pendingRes.getStatus());

        // Canceling the pending checkout on Free re-enables account deletion
        UserResource.applyPlanSync(user, new PlanSyncRequest("cancel", null, null, null));
        assertNull(UserResource.cancelSubscriptionAndValidateDeletion(user, billing));

        // Pro and Premium users cancel their Stripe subscription and confirm before deletion
        user.plan = "pro";
        user.stripeSubscriptionId = "sub_pro_123";
        assertNull(UserResource.cancelSubscriptionAndValidateDeletion(user, billing));
        assertEquals("free", user.plan);
        assertEquals(User.PLAN_STATUS_CANCELED, user.planStatus);
        assertNull(user.stripeSubscriptionId);

        user.plan = "premium";
        user.stripeSubscriptionId = "sub_premium_456";
        assertNull(UserResource.cancelSubscriptionAndValidateDeletion(user, billing));
        assertEquals("free", user.plan);
        assertEquals(User.PLAN_STATUS_CANCELED, user.planStatus);
        assertNull(user.stripeSubscriptionId);
    }
}
