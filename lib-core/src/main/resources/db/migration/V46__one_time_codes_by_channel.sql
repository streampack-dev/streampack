-- One-time codes are keyed by delivery channel and recipient, not only by email (#53).
ALTER TABLE one_time_codes RENAME COLUMN email TO recipient;
ALTER TABLE one_time_codes ADD COLUMN channel VARCHAR(20) NOT NULL DEFAULT 'EMAIL';
CREATE INDEX IF NOT EXISTS idx_otc_channel_recipient ON one_time_codes(channel, recipient);
