-- Work list: one row per engagement to load (data-model.md, "work_item").
create table work_item (
    id              uuid         primary key,
    engagement_id   varchar(64)  not null,
    firm_id         varchar(64)  not null,
    reason          varchar(16)  not null check (reason in ('ACTIVATION', 'PUBLISH', 'SWEEP')),
    state           varchar(16)  not null check (state in ('OUTSTANDING', 'IN_FLIGHT', 'DONE', 'PARKED')),
    attempt         integer      not null check (attempt >= 0),
    added_at        timestamptz  not null,
    next_attempt_at timestamptz  not null,
    lease_until     timestamptz,
    last_error      varchar(32),
    version         bigint       not null,
    updated_at      timestamptz  not null,
    -- A lease is set while a load may be running: always when IN_FLIGHT, never when OUTSTANDING or
    -- PARKED, and possibly when DONE (marked done while its load was still running).
    constraint ck_work_item_lease_in_flight check (state <> 'IN_FLIGHT' or lease_until is not null),
    constraint ck_work_item_no_lease_when_waiting
        check (state not in ('OUTSTANDING', 'PARKED') or lease_until is null)
);

-- At most one open item per engagement (FR-013).
create unique index ux_work_item_open_engagement
    on work_item (engagement_id) where state in ('OUTSTANDING', 'IN_FLIGHT');

-- Due outstanding work per firm, oldest first.
create index ix_work_item_due on work_item (firm_id, added_at) where state = 'OUTSTANDING';

-- Loads in flight (lease_until > now, any state) and expired leases.
create index ix_work_item_lease on work_item (lease_until) where lease_until is not null;
