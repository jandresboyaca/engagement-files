-- Local profile only: a few outstanding items for three firms of different sizes. WireMock answers
-- eng-missing with 404 (parked at once) and eng-flaky with 503 twice, then 204 (wiremock/mappings).
insert into work_item (id, engagement_id, firm_id, reason, state, attempt, added_at, next_attempt_at,
                       lease_until, last_error, version, updated_at)
select gen_random_uuid(), seed.engagement_id, seed.firm_id, seed.reason, 'OUTSTANDING', 0,
       now() - seed.age, now() - seed.age, null, null, 0, now()
from (values
    ('eng-large-01', 'firm-large', 'SWEEP', interval '60 minutes'),
    ('eng-large-02', 'firm-large', 'SWEEP', interval '59 minutes'),
    ('eng-large-03', 'firm-large', 'SWEEP', interval '58 minutes'),
    ('eng-large-04', 'firm-large', 'SWEEP', interval '57 minutes'),
    ('eng-large-05', 'firm-large', 'SWEEP', interval '56 minutes'),
    ('eng-large-06', 'firm-large', 'SWEEP', interval '55 minutes'),
    ('eng-large-07', 'firm-large', 'SWEEP', interval '54 minutes'),
    ('eng-large-08', 'firm-large', 'SWEEP', interval '53 minutes'),
    ('eng-large-09', 'firm-large', 'SWEEP', interval '52 minutes'),
    ('eng-large-10', 'firm-large', 'SWEEP', interval '51 minutes'),
    ('eng-medium-01', 'firm-medium', 'PUBLISH', interval '30 minutes'),
    ('eng-medium-02', 'firm-medium', 'PUBLISH', interval '29 minutes'),
    ('eng-medium-03', 'firm-medium', 'PUBLISH', interval '28 minutes'),
    ('eng-missing', 'firm-medium', 'PUBLISH', interval '27 minutes'),
    ('eng-small-01', 'firm-small', 'ACTIVATION', interval '10 minutes'),
    ('eng-flaky', 'firm-small', 'ACTIVATION', interval '9 minutes')
) as seed (engagement_id, firm_id, reason, age);
