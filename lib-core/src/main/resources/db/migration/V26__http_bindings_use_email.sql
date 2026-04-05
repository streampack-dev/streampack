-- Migrate HTTP ServiceBinding externalIdentifier from username to email
-- Email is now the identity anchor for both OTP and OIDC auth
UPDATE service_bindings sb SET external_identifier = u.email
FROM users u WHERE sb.user_id = u.id AND sb.protocol = 'HTTP' AND u.email != '';
