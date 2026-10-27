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
- `POST /api/users/{id}/plan/checkout` with `plan: free` cancels the stored
  subscription (`DELETE /v1/subscriptions/{id}`) **before** writing `plan=free`;
  an unconfirmed cancellation (or a missing API key while a subscription id
  exists) returns 502 and leaves the paid plan unchanged.
- Feature switch `notearray.billing.enabled` (default `true`): `false` hides the
  plans card and returns 503 from `/plan/checkout`. It does not cancel existing
  subscriptions; webhooks, downgrade/deletion cancellation and pending/past-due
  alerts keep working.
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

## Layer 3 — done: webhook lifecycle events

Client-callable `past_due` / `subscription_deleted` were **removed** from
`applyPlanSync`; the only remaining sync actions are `confirm` (Layer 2) and
`cancel` (pending-checkout cleanup). Billing events arrive exclusively as signed
Stripe webhooks.

Implementation (`StripeWebhookResource`, branch `agent/stripe-webhook`):

1. **Endpoint**: `POST /api/stripe/webhook`, public (no session auth), reads the
   **raw body bytes** so the signature covers exactly what Stripe sent; `@Transactional`.
   - `CsrfFilter` verified to pass header-less (non-browser) requests unchanged —
     no filter changes needed.
   - Unconfigured `notearray.stripe.webhook-secret` → 503 (never processes bodies).
2. **Signature verification** (dependency-free, no stripe-java):
   parse `Stripe-Signature: t=<ts>,v1=<hex>...`; HMAC-SHA256 over `"<t>.<rawBody>"`
   via JCA (`HmacSHA256`), digest compared in constant time (`MessageDigest.isEqual`),
   multiple `v1` values each checked, hex case-insensitive; reject when
   `|now - t| > 300 s` (replay) — bad/missing signature → 400 before any DB touch.
   Decision recorded: stripe-java's `Webhook.constructEvent` was considered and
   rejected for footprint (~10 MB deps for 2 tiny handlers); revisit if richer
   Stripe object processing is ever needed.
3. **Events handled** (downgrade-only — a webhook can never raise a plan):
   - `invoice.payment_failed` → `StripeWebhookResource.markPastDue`: keeps the
     plan, sets `plan_status=past_due`, idempotent (re-deliveries don't move
     `planUpdatedAt`).
   - `customer.subscription.deleted` → `downgradeAfterDeletion`: `plan=free`,
     `canceled`, clears `stripeSubscriptionId` + `pendingPlan`; **keeps**
     `stripeCustomerId` (real-payment identity for later verification).
   - Every other event type → 200 acknowledged, ignored (Stripe stops retrying).
4. **User matching**: `User.findByStripeSubscriptionId` (event: invoice
   `subscription` / subscription `id`), fallback `User.findByStripeCustomerId`
   (`customer`). Unmatched users → 200 `{ignored:true}` + warn log.
5. **Tests** (`StripeWebhookResourceTest`, 15 cases): HMAC pinned to the RFC 4231
   "Jefe" vector; accept correct signature, header parse edge cases, ±skew
   boundaries (300 s passes, 301 fails, future-dated fails), wrong secret,
   tampered body, multi-`v1` acceptance; lifecycle handlers keep/clear exactly
   the right fields; sync endpoint asserts legacy client actions now 400
   while `cancel`/`confirm` round-trip. Suite 162 tests / 24 classes.

### Stripe-side prerequisite (user action, one-time)

Dashboard → Developers → Webhooks → Add endpoint:
URL `https://<public host>/api/stripe/webhook`, events
`invoice.payment_failed` + `customer.subscription.deleted`; then start the
backend with `-Dnotearray.stripe.webhook-secret=whsec_...` (a *different*
secret from the API key). Validate with the dashboard's "Send test webhook";
a deliberately bogus signature must return 400.

## Useful verification endpoints

- The billing UI: `/settings` → Available Plans.
- Manual confirm test (should 422 without a real paid session):
  `curl -X POST /api/users/<id>/plan/sync -H 'Content-Type: application/json'
     --cookie <session> -d '{"action":"confirm","checkoutSessionId":"cs_test_XXXXXXXXXXXXXXXXXXXX"}'`
- Users CRUD must refuse: `PUT /api/users/<id>` with `{"plan":"premium"}`
  → 400 "plan cannot be set directly".
