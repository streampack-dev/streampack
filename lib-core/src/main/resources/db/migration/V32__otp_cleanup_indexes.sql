-- Improve OTP stale-row cleanup performance.
CREATE INDEX IF NOT EXISTS idx_otc_expires_at ON one_time_codes(expires_at);
CREATE INDEX IF NOT EXISTS idx_otc_used_at_nonnull ON one_time_codes(used_at) WHERE used_at IS NOT NULL;
