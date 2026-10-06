-- Mattermost channel ids don't say what kind of channel they are; the registered direct and
-- group channels do. (Direct messages never registered can't be told apart in what was logged.)
update message_log m set direct = true
from mattermost_channels c
join mattermost_servers s on s.id = c.server_id
where c.channel_type in ('D', 'G')
  and m.provenance_uri = 'mattermost://' || s.name || '/' || c.channel_id;
