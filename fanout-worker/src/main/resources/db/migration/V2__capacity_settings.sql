-- C and F, changeable while the worker runs (FR-007); exactly one row.
create table capacity_settings (
    id             smallint     primary key check (id = 1),
    global_limit   integer      not null check (global_limit >= 0),
    per_firm_limit integer      not null check (per_firm_limit >= 0),
    updated_at     timestamptz  not null,
    constraint ck_capacity_settings_per_firm_within_global check (per_firm_limit <= global_limit)
);
