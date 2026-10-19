-- A comment can quote the passage of its post that it is about (#256), as a W3C Web Annotation
-- text quote selector: the quoted text (exact), and some of the post's text before it (prefix)
-- and after it (suffix), so that a front end can tell apart a passage that occurs more than once.
-- The quote is kept apart from markdown_source, so that an edit of the comment can't change it.
-- Only a top-level comment can quote; a reply is about its parent. A CHECK passes when its test
-- is NULL, so the second case says anchor_exact IS NOT NULL: without it, a prefix with no quote
-- would pass, because char_length(NULL) is NULL.
ALTER TABLE comments
    ADD COLUMN anchor_exact  TEXT,
    ADD COLUMN anchor_prefix TEXT,
    ADD COLUMN anchor_suffix TEXT,
    ADD CONSTRAINT comments_anchor_shape CHECK (
        (anchor_exact IS NULL AND anchor_prefix IS NULL AND anchor_suffix IS NULL)
        OR (anchor_exact IS NOT NULL
            AND char_length(anchor_exact) BETWEEN 1 AND 500
            AND coalesce(char_length(anchor_prefix), 0) <= 64
            AND coalesce(char_length(anchor_suffix), 0) <= 64)),
    ADD CONSTRAINT comments_anchor_top_level CHECK (
        anchor_exact IS NULL OR parent_comment_id IS NULL);
