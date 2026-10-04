CREATE TABLE publishing_accounts
(
    id                         uuid PRIMARY KEY,
    platform                   varchar(50)  NOT NULL DEFAULT 'TIKTOK',
    account_id                 varchar(200) NOT NULL,
    username                   varchar(200),
    display_name               varchar(200),
    avatar_url                 text,
    access_token               text         NOT NULL,
    refresh_token              text         NOT NULL,
    token_expires_at           timestamptz  NOT NULL,
    refresh_expires_at         timestamptz,
    scopes                     text         NOT NULL DEFAULT '',
    privacy_level_options      jsonb        NOT NULL DEFAULT '["SELF_ONLY"]',
    comment_disabled           boolean      NOT NULL DEFAULT false,
    duet_disabled              boolean      NOT NULL DEFAULT false,
    stitch_disabled            boolean      NOT NULL DEFAULT false,
    max_video_post_duration_sec integer     NOT NULL DEFAULT 600,
    is_active                  boolean      NOT NULL DEFAULT true,
    created_at                 timestamptz  NOT NULL DEFAULT now(),
    updated_at                 timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (platform, account_id)
);

CREATE TABLE publishing_batches
(
    id               uuid PRIMARY KEY,
    project_id       uuid         NOT NULL REFERENCES projects (id),
    account_id       uuid         NOT NULL REFERENCES publishing_accounts (id),
    platform         varchar(50)  NOT NULL DEFAULT 'TIKTOK',
    name             varchar(200) NOT NULL,
    status           varchar(30)  NOT NULL CHECK (status IN ('DRAFT', 'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'PAUSED', 'CANCELLED')),
    archive_key      text,
    archive_sha256   char(64),
    archive_name     varchar(200),
    idempotency_key  varchar(160) NOT NULL UNIQUE,
    total_tasks      integer      NOT NULL DEFAULT 0,
    valid_tasks      integer      NOT NULL DEFAULT 0,
    invalid_tasks    integer      NOT NULL DEFAULT 0,
    published_tasks  integer      NOT NULL DEFAULT 0,
    processing_tasks integer      NOT NULL DEFAULT 0,
    queued_tasks     integer      NOT NULL DEFAULT 0,
    failed_tasks     integer      NOT NULL DEFAULT 0,
    cancelled_tasks  integer      NOT NULL DEFAULT 0,
    paused           boolean      NOT NULL DEFAULT false,
    cancelled        boolean      NOT NULL DEFAULT false,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    started_at       timestamptz,
    completed_at     timestamptz
);

CREATE TABLE publishing_tasks
(
    id                       uuid PRIMARY KEY,
    batch_id                 uuid         NOT NULL REFERENCES publishing_batches (id),
    platform                 varchar(50)  NOT NULL DEFAULT 'TIKTOK',
    video_filename           varchar(300) NOT NULL,
    video_storage_key        text,
    video_sha256             char(64),
    video_size_bytes         bigint       NOT NULL DEFAULT 0,
    duration_seconds         double precision,
    width                    integer,
    height                   integer,
    caption                  text,
    privacy_level            varchar(60)  NOT NULL DEFAULT 'SELF_ONLY',
    disable_comment          boolean      NOT NULL DEFAULT false,
    disable_duet             boolean      NOT NULL DEFAULT false,
    disable_stitch           boolean      NOT NULL DEFAULT false,
    video_cover_timestamp_ms bigint       NOT NULL DEFAULT 1000,
    source_generation_id     uuid REFERENCES generations (id),
    source_asset_id          uuid REFERENCES assets (id),
    thumbnail_storage_key    text,
    status                   varchar(30)  NOT NULL CHECK (status IN ('DRAFT', 'QUEUED', 'UPLOADING', 'PROCESSING', 'PUBLISHED', 'FAILED', 'CANCELLED')),
    validation_error         text,
    publish_id               varchar(255),
    upload_url               text,
    post_id                  varchar(255),
    post_url                 text,
    attempts                 integer      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    retry_count              integer      NOT NULL DEFAULT 0,
    poll_failures            integer      NOT NULL DEFAULT 0 CHECK (poll_failures >= 0),
    last_error_code          varchar(100),
    last_error_message       text,
    available_at             timestamptz  NOT NULL DEFAULT now(),
    lease_token              uuid,
    lease_until              timestamptz,
    created_at               timestamptz  NOT NULL DEFAULT now(),
    started_at               timestamptz,
    completed_at             timestamptz
);

CREATE INDEX publishing_task_claim ON publishing_tasks (available_at, created_at)
    WHERE status IN ('QUEUED', 'UPLOADING', 'PROCESSING');
CREATE INDEX publishing_task_batch ON publishing_tasks (batch_id, created_at, id);
CREATE INDEX publishing_task_publish_id ON publishing_tasks (publish_id) WHERE publish_id IS NOT NULL;
CREATE INDEX publishing_task_sha ON publishing_tasks (video_sha256) WHERE video_sha256 IS NOT NULL;

CREATE TABLE publishing_events
(
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id   uuid        NOT NULL REFERENCES publishing_batches (id),
    task_id    uuid REFERENCES publishing_tasks (id),
    action     varchar(80) NOT NULL,
    detail     jsonb       NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX publishing_events_batch ON publishing_events (batch_id, id);
