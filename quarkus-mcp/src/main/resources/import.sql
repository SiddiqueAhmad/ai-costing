-- Machine catalog, joined from machine_activity.machine_id -> machine.id
-- (see MachineActivity#machine). Rates mirror CostingService's config-based
-- defaults, exposed here too so join queries can compute cost without the
-- Java-side rate lookup.
insert into machine (id, name, hourly_rate) values
    ('1', 'CNC Mill 1', 5000),
    ('2', 'CNC Mill 2', 3500);

-- Sample machine activity data, mirroring the columns produced by the
-- Streamlit app (app.py) at the repository root: machine_id, activity_type,
-- start_time, end_time, remark, submitted_by.
--
-- Ids are pulled from machine_activity_SEQ (the same sequence Hibernate uses
-- for entities persisted at runtime, e.g. via the logActivity MCP tool) so
-- sample data can't collide with ids generated later. Note: Hibernate's
-- default pooled sequence optimizer uses allocationSize=50, so nextval()
-- here returns 1, 51, 101, ... -- not 1, 2, 3. Don't assume sequential ids.
insert into machine_activity (id, machine_id, activity_type, start_time, end_time, remark, submitted_by) values
    (nextval('machine_activity_SEQ'), '1', 'Running',     '2026-07-01 08:00:00', '2026-07-01 12:00:00', 'Batch A',              'operator1'),
    (nextval('machine_activity_SEQ'), '1', 'Setup',       '2026-07-01 12:00:00', '2026-07-01 12:30:00', 'Tooling change',        'operator1'),
    (nextval('machine_activity_SEQ'), '1', 'Running',     '2026-07-01 12:30:00', '2026-07-01 17:00:00', 'Batch B',              'operator1'),
    (nextval('machine_activity_SEQ'), '2', 'Running',     '2026-07-01 09:00:00', '2026-07-01 13:00:00', 'Batch C',              'operator2'),
    (nextval('machine_activity_SEQ'), '2', 'Idle',        '2026-07-01 13:00:00', '2026-07-01 13:45:00', 'Waiting on material',  'operator2'),
    (nextval('machine_activity_SEQ'), '2', 'Maintenance', '2026-07-01 13:45:00', '2026-07-01 14:30:00', 'Scheduled maintenance', 'operator2'),
    (nextval('machine_activity_SEQ'), '2', 'Running',     '2026-07-01 14:30:00', '2026-07-01 18:00:00', 'Batch D',              'operator2');
