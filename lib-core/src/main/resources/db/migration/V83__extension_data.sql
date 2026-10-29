-- Data the server keeps for extensions (#294): one JSON document per (extension, collection, item).
CREATE TABLE extension_data (
    id          UUID PRIMARY KEY,
    extension   VARCHAR(100) NOT NULL,
    collection  VARCHAR(100) NOT NULL,
    item_id     VARCHAR(255) NOT NULL,
    data        JSONB NOT NULL DEFAULT '{}',
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_extension_data UNIQUE (extension, collection, item_id)
);

-- Hangman's blocked words move into it. hangman_blocked_words stays for one release, so the
-- release before this one can still run; a later migration drops it.
INSERT INTO extension_data (id, extension, collection, item_id)
SELECT id, 'hangman', 'blocked-words', word FROM hangman_blocked_words
ON CONFLICT (extension, collection, item_id) DO NOTHING;
