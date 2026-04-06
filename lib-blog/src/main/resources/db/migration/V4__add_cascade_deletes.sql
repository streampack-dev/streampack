-- Add ON DELETE CASCADE to all foreign keys referencing posts(id)
-- so hard-deleting a post automatically removes dependent rows

ALTER TABLE slugs
    DROP CONSTRAINT slugs_post_id_fkey,
    ADD CONSTRAINT slugs_post_id_fkey
        FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE;

ALTER TABLE comments
    DROP CONSTRAINT comments_post_id_fkey,
    ADD CONSTRAINT comments_post_id_fkey
        FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE;

ALTER TABLE post_categories
    DROP CONSTRAINT post_categories_post_id_fkey,
    ADD CONSTRAINT post_categories_post_id_fkey
        FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE;

ALTER TABLE post_tags
    DROP CONSTRAINT post_tags_post_id_fkey,
    ADD CONSTRAINT post_tags_post_id_fkey
        FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE;

-- Cascade parent comment deletion to child comments
ALTER TABLE comments
    DROP CONSTRAINT comments_parent_comment_id_fkey,
    ADD CONSTRAINT comments_parent_comment_id_fkey
        FOREIGN KEY (parent_comment_id) REFERENCES comments(id) ON DELETE CASCADE;
