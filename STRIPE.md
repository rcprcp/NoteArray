# Stripe Integration Plan

This file is the source of truth for the Stripe billing integration and the plan
fix for **REVIEW finding #26** (plan self-escalation via API). Created while
implementing Layers 1 + 2 on branch `agent/plan-stripe-verification`.

## The security model

**Only Stripe's API may change or activate a plan tier.** Nothing a client sends
in a request body is ever trusted for billing state. Concretely:

- `POST /api/users` and `PUT /api/users/{id}` **reject** `plan` with 400
  ("plan cannot be set directly; use /plan/checkout"). The field stays in the
  request record purely so it can be rejected explicitly (Jackson otherwise
  silently drops unknown fields).
- `POST /api/users/{id}/plan/sync` accepts `{action, checkoutSessionId}` only.
  `applyPlanSync(user, req, billing)` writes `plan` / `stripeCustomerId` /
  `stripeSubscriptionId` **exclusively from `StripeBillingService
  .verifyCheckoutSession(...)` output**, which calls Stripe with the server API
  key. Unverifiable confirm → 422; pending checkout state is kept so it can be retried.
- Downgrade-ish sync actions (`cancel`, `past_due`, `subscription_deleted`) are
  client-callable but can only *lower* billing state — they are interim until
  Layer 3 moves them behind the webhook.
- `verifyCheckoutSession` defenses: session IDs must match `cs_(test|live)_<alnum>`
  before being used in a Stripe URL; the session must be `status=complete` **and**
  `payment_status=paid`; the subscription price ID is mapped server-side; a
  session without a subscription or an unmapped price is refused.

## Layer 1 — done: remove plan from user-writable CRUD

`planWriteRejected()` in `UserResource`; tested by
`UserPlanSyncTest.userCrudCannotWriteThePlanDirectly`.

## Layer 2 — done: server-verified checkout confirm

- `StripeBillingService.verifyCheckoutSession(sessionId)`:
  1. `GET /v1/checkout/sessions/{id}` — require `status=complete`, `payment_status=paid`.
  2. `GET /v1/subscriptions/{subId}?expand[]=items.data.price` — take `items[0].price.id`.
  3. `planForPrice(priceId, proPriceId, premiumPriceId)` → `pro` / `premium` / null.
  4. Return `VerifiedCheckout(plan, customer, subscriptionId)`; the resource writes them.
- Config keys (all under `notearray.stripe.*`):
  - `api-key` — server secret key (used for the GET calls).
  - `api-base-url` — default `https://api.stripe.com`.
  - `pro-price-id`, `premium-price-id` — **required for confirm to work**;
    subscription prices must be mapped or activation is refused with a warn log.
  - `pro-checkout-url`, `premium-checkout-url` — Payment Links (client redirect target).

### Stripe-side prerequisites (user action, one-time)

1. Edit both Payment Links ("After payment"):
   the return/success URL must embed the placeholder
   `{CHECKOUT_SESSION_ID}`, e.g.
   `https://<your-host>/settings?stripe=success&session_id={CHECKOUT_SESSION_ID}`
2. Set the env vars / system properties at backend start:
   `-Dnotearray.stripe.pro-price-id=price_...`
   `-Dnotearray.stripe.premium-price-id=price_...`
   (plus the existing `api-key` and checkout URLs).
3. Restart the backend.

### What the UI does now

- After the Stripe redirect, `PersonalSettings.jsx` reads only `session_id` and
  POSTs `{action:'confirm', checkoutSessionId}`. A success redirect **without**
  `session_id` is treated as unverifiable: pending checkout is cleared
  (`cancel`) and a warning notification explains it.
- The manual "Confirm Stripe Payment" button was removed (a manual button
  cannot prove payment); pending users are pointed back at the checkout flow.
  "Cancel Checkout" remains.

## Layer 3 — todo: webhook lifecycle events

Goal: replace the client-callable `past_due` / `subscription_deleted` sync
actions with signed Stripe webhook processing so no sync action at all can
escalate or fake billing state from the client.

Implementation sketch (for a future branch, e.g. `agent/stripe-webhook`):

1. **Endpoint**: new `StripeWebhookResource` at `POST /api/stripe/webhook`,
   reads raw body + `Stripe-Signature` header.
   - Exempt from session auth. `CsrfFilter` must be updated to allowlist this
     non-browser path (no `Sec-Fetch-Site`/`Origin` from curl → already passes,
     but verify the filter's behavior for header-less requests).
   - Signature check: hand-rolled HMAC-SHA256 (no stripe-java dependency):
     parse `Stripe-Signature: t=<ts>,v1=<sig>`; notearray-side secret in
     `notearray.stripe.webhook-secret`; compare `HMAC(secret, t + "." + body)`
     against every `v1` value with a constant-time comparison (`MessageDigest.isEqual`);
     reject when |now - t| > tolerance (e.g. 5 minutes) to stop replay.
   - Respond 200 fast; parse tolerantly (webhook retries on 5xx).
2. **Events to handle**:
   - `invoice.payment_failed` → find user by `stripeCustomerId` → set
     `planStatus = past_due` (data comes from the event object only).
   - `customer.subscription.deleted` → downgrade: `plan=free`,
     `planStatus=canceled`, clear `stripeSubscriptionId`, clear `pendingPlan`.
   - Optionally `invoice.paid`/`customer.subscription.updated` → keep active
     status / detect cancellations at period end.
3. **Lookups required**: `User.findByStripeCustomerId(cusId)` Panache finder
   (add `@Indexed`-style DB migration if the query stays slow — optional).
4. **Then delete** the `past_due` and `subscription_deleted` cases from
   `applyPlanSync` (client loses the ability to report billing events at all).
   Keep `cancel` locally-scope (pending checkout cleanup) and `confirm`.

## Useful verification endpoints

- The billing UI: `/settings` → Available Plans.
- Manual confirm test (should 422 without a real paid session):
  `curl -X POST /api/users/<id>/plan/sync -H 'Content-Type: application/json'
     --cookie <session> -d '{"action":"confirm","checkoutSessionId":"cs_test_XXXXXXXXXXXXXXXXXXXX"}'`
- Users CRUD must refuse: `PUT /api/users/<id>` with `{"plan":"premium"}`
  → 400 "plan cannot be set directly".
