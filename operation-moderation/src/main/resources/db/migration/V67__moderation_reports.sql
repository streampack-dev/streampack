-- Abuse detection (#150): what the hourly review found, for an admin to decide on. Nothing in
-- these tables is acted on automatically.
create table moderation_report (
    id uuid primary key,
    provenance_uri varchar(500) not null,
    protocol varchar(20) not null,
    service_id varchar(255),
    sender varchar(255) not null,
    user_id uuid,
    score double precision not null,
    signals jsonb not null default '{}'::jsonb,
    verdict_abusive boolean,
    verdict_reason text,
    verdict_model varchar(100),
    excerpt_line_ids jsonb not null default '[]'::jsonb,
    flagged_line_ids jsonb not null default '[]'::jsonb,
    cited_line_ids jsonb not null default '[]'::jsonb,
    window_start timestamp with time zone not null,
    window_end timestamp with time zone not null,
    status varchar(20) not null default 'OPEN',
    acted_by varchar(255),
    acted_by_id uuid,
    acted_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index moderation_report_status_created on moderation_report (status, created_at desc);

-- Every admin action, on a report or on log lines directly: who, what, when.
create table moderation_action (
    id uuid primary key,
    report_id uuid references moderation_report (id) on delete set null,
    action varchar(20) not null,
    provenance_uri varchar(500),
    line_ids jsonb not null default '[]'::jsonb,
    note text,
    actor varchar(255) not null,
    actor_id uuid,
    acted_at timestamp with time zone not null
);

create index moderation_action_report on moderation_action (report_id, acted_at);
