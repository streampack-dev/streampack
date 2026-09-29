-- Sign-in finds an account by its lowercased email and expects at most one. Addresses written before
-- normalization (profile edits, admin edits, ADMIN_EMAIL) are normalized, and a second account with
-- the same address is refused from here on. Accounts from chat identities have a blank email and are
-- exempt. If existing rows collide once normalized this migration fails, which is deliberate: which
-- account keeps the address is a decision for a person (see the query in the pull request).
UPDATE users SET email = lower(trim(email)) WHERE email <> lower(trim(email));

CREATE UNIQUE INDEX uq_users_email ON users (email) WHERE email <> '';
