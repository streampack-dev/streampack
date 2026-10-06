-- Slack channel settings were kept under the channel's name (slack://ws/%23java) while its
-- messages arrive under its id (slack://ws/C0123ABCD), so mute, logged and visible never applied.
-- Move the settings of every channel whose id is known onto the id. Channels with no id keep
-- theirs until `slack join` finds the id.
update channel_control_options o
set provenance_uri = 'slack://' || w.name || '/' || c.channel_id,
    updated_at = now()
from slack_channels c
join slack_workspaces w on w.id = c.workspace_id
where c.channel_id is not null
  and c.deleted = false
  and o.provenance_uri = 'slack://' || w.name || '/' || replace(c.name, '#', '%23')
  and not exists (
      select 1 from channel_control_options taken
      where taken.provenance_uri = 'slack://' || w.name || '/' || c.channel_id
  );
