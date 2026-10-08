-- What each logged line is (#174): something said, or a channel event.
alter table message_log add column kind varchar(20) not null default 'MESSAGE';

-- Backfill. Only IRC logged events, as text lines from its adapter, attributed to the nick they're
-- about (or "unknown", before #124). A `/me` action is logged as "* nick action" too, from the
-- nick, so the text alone could be either; these rules take only what an event can produce:
--
-- * Joins, parts and topics went to the channel itself. A line counts only when it's the whole
--   event format and names its own channel (joins and parts), and its sender is the nick in it or
--   "unknown". A `/me joined #thischannel`, word for word, is the one case left; it's taken as a
--   join.
-- * Quits and nick changes went to the pseudo-channel "*", which nothing but events ever logged
--   to, so a line there is certain. The same text in a channel is someone's `/me`, and stays a
--   message.
--
-- The channel in a channel's address is percent-encoded ("#" is %23); one with any other encoded
-- character doesn't match, and its events stay messages.

update message_log m set kind = c.kind
from (
    select id,
           case
               when content ~ '^\* (\S+) joined (\S+)$'
                    and substring(content from '^\* \S+ joined (\S+)$') = chan
                    and sender in (substring(content from '^\* (\S+) '), 'unknown')
                   then 'JOIN'
               when content ~ '^\* (\S+) left (\S+)( \(.*\))?$'
                    and substring(content from '^\* \S+ left (\S+)') = chan
                    and sender in (substring(content from '^\* (\S+) '), 'unknown')
                   then 'PART'
               when content ~ '^\* (\S+) changed the topic to: '
                    and sender in (substring(content from '^\* (\S+) '), 'unknown')
                   then 'TOPIC'
           end as kind
    from (
        select id, content, sender,
               replace(substring(provenance_uri from '^irc://[^/]*/(.*)$'), '%23', '#') as chan
        from message_log
        where provenance_uri like 'irc://%'
          and direction = 'INBOUND'
          and content like '* %'
    ) irc
) c
where m.id = c.id and c.kind is not null;

update message_log set kind = case
        when content ~ '^\* \S+ quit( \(.*\))?$' then 'QUIT'
        when content ~ '^\* \S+ is now known as \S+$' then 'NICK'
        else kind
    end
where provenance_uri ~ '^irc://[^/]*/\*$'
  and direction = 'INBOUND';
