-- Outgoing links (#112 mentions, #128 autosubscribe) are handled for posts published or edited from
-- now on. Posts already live are marked as handled through their last update, and as a baseline:
-- the first time one is looked at again (an edit, a factoid re-render), its links are recorded as
-- handled without being sent, so no old post's links are mentioned or subscribed to all at once.
UPDATE posts
SET metadata = metadata || jsonb_build_object(
        'linksCheckedThrough', FLOOR(EXTRACT(EPOCH FROM updated_at) * 1000)::bigint,
        'linksBaseline', true)
WHERE status = 'APPROVED'
  AND deleted = false
  AND published_at <= now();
