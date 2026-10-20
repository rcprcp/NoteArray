-- Stripe subscription sync state for user plans.
ALTER TABLE users
    ADD COLUMN plan_status VARCHAR(32) NOT NULL DEFAULT 'active',
    ADD COLUMN pending_plan VARCHAR(32),
    ADD COLUMN stripe_customer_id VARCHAR(255),
    ADD COLUMN stripe_subscription_id VARCHAR(255),
    ADD COLUMN plan_updated_at TIMESTAMP;
