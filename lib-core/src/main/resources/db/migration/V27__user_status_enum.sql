-- Replace boolean deleted flag with status enum on users table
ALTER TABLE users ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
UPDATE users SET status = 'ERASED' WHERE deleted = true;
ALTER TABLE users DROP COLUMN deleted;
DROP INDEX IF EXISTS idx_users_active;
CREATE INDEX idx_users_status ON users(status);

-- Service bindings cascade with user deletion so orphan cleanup cannot be forgotten
ALTER TABLE service_bindings DROP CONSTRAINT service_bindings_user_id_fkey,
  ADD CONSTRAINT service_bindings_user_id_fkey
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- Verification tokens cascade with user deletion
ALTER TABLE verification_tokens DROP CONSTRAINT verification_tokens_user_id_fkey,
  ADD CONSTRAINT verification_tokens_user_id_fkey
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
