-- Abuse detection (#150). An admin can hide a logged line from public view: it's kept, and left
-- out of every read the way a direct line is.
alter table message_log add column hidden boolean not null default false;

-- A logged channel is watched for abuse unless it opts out.
alter table channel_control_options add column moderated boolean not null default true;
