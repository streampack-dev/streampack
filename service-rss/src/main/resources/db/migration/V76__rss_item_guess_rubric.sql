-- Which rubric produced each guess (#192): the first 12 hex characters of the SHA-256 of the
-- rendered rubric (the rss-rating-rubric prompt, before the answer's instructions and the examples
-- are appended). The rubric can now change without a release; null for guesses made before this.
ALTER TABLE rss_item_guess ADD COLUMN rubric VARCHAR(16);
