-- BT Viewer field log. Run once in Supabase: SQL Editor -> New query -> Run.
-- The app inserts one row per batch (about one a minute while it is open).

create table if not exists public.telemetry (
  id          bigint generated always as identity primary key,
  created_at  timestamptz not null default now(),
  sid         text   not null,   -- random id per app session
  seq         int    not null,   -- batch number within the session
  app         text,              -- app version
  device      text,              -- phone model / Android version
  t_ms        bigint,            -- ms since session start, at send time
  log         text   not null    -- "t cm alert lag" lines; '#' lines are events
);

create index if not exists telemetry_sid_seq on public.telemetry (sid, seq);

-- The key inside the APK is public, so it may only INSERT. Reading is done
-- from the dashboard (or the service key), never from the app.
alter table public.telemetry enable row level security;

drop policy if exists "app can insert" on public.telemetry;
create policy "app can insert" on public.telemetry
  for insert to anon with check (length(log) < 1000000);
