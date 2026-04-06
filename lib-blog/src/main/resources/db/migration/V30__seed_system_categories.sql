-- Seed system categories for CMS-managed pages and sidebar content
INSERT INTO categories (id, name, slug, deleted)
VALUES
    (gen_random_uuid(), '_pages', '_pages', false),
    (gen_random_uuid(), '_sidebar', '_sidebar', false)
ON CONFLICT (name) DO NOTHING;
