create table mattermost_servers (
    id uuid primary key,
    name varchar(100) not null unique,
    base_url varchar(500) not null,
    token varchar(500) not null,
    signal_character varchar(10),
    autoconnect boolean not null default false,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    deleted boolean not null default false
);

create table mattermost_channels (
    id uuid primary key,
    server_id uuid not null references mattermost_servers(id),
    name varchar(200) not null,
    channel_id varchar(64) not null,
    team_id varchar(64),
    channel_type varchar(10),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    deleted boolean not null default false
);

create unique index uq_mattermost_channel_server_channel_id
    on mattermost_channels(server_id, channel_id)
    where deleted = false;

create unique index uq_mattermost_channel_server_name
    on mattermost_channels(server_id, name)
    where deleted = false;
