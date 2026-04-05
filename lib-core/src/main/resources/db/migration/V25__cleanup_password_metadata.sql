-- Remove passwordHash from HTTP ServiceBinding metadata (no longer used after OTP migration)
UPDATE service_bindings SET metadata = metadata - 'passwordHash' WHERE metadata ? 'passwordHash';
