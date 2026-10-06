-- Direct conversations (private messages, DMs, group DMs) are logged but never read back out.
alter table message_log add column direct boolean not null default false;

-- What was logged before, by the address forms that mark a direct conversation:
-- an IRC target that isn't a channel (#, encoded as %23, &, + or !), a Slack user id,
-- and a Discord target with no guild.
update message_log set direct = true
where (provenance_uri ~ '^irc://[^/]*/' and provenance_uri !~ '^irc://[^/]*/(%23|&|\+|!)')
   or provenance_uri ~ '^slack://[^/]*/[UW][A-Z0-9]{2,}$'
   or provenance_uri ~ '^discord:///';
