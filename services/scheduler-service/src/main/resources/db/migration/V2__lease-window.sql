-- The lease keys the fired window, not the wall clock. The pre-V2 lease covered
-- the whole inter-fire interval (locked_until = next_fire + lease), so the next
-- due window — whose next_fire_at the scan had already advanced — could never
-- acquire: every job with a cron period longer than the lease fired at half its
-- intended rate. V2 gates on the fired window: a lease taken for window N never
-- suppresses window N+1, while two replicas scanning the same window still
-- single-fire (the scan race spans seconds, not the cron period). locked_until
-- stays as observability for the last fire.
ALTER TABLE sched_leases ADD COLUMN fired_window timestamptz;
