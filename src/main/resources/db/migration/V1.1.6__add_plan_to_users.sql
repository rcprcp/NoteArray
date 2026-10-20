-- Subscription plan tier for each user ('free', 'pro', 'premium'), defaulting to 'free'.
ALTER TABLE users ADD COLUMN plan VARCHAR(32) NOT NULL DEFAULT 'free';
