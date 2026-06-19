-- Family Time Manager Supabase all-in-one setup
-- Run this single file in Supabase SQL Editor.
-- Generated from the previously split schema/RPC/RLS/feature SQL files.
-- Safe to re-run: statements are written as idempotent create/replace/drop-if-exists where possible.
-- NOTE: This file intentionally excludes CLEAR_APP_DATA, draft SQL, and dev-only patch files.


-- =============================================================================
-- BEGIN docs\SUPABASE_SETUP_DEV.sql
-- =============================================================================

-- Family Time Manager development Supabase setup.
-- Run this in Supabase Dashboard -> SQL Editor -> New query.
-- These policies are for development testing only. Do not ship production with anon-all policies.

create extension if not exists "pgcrypto";

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  email text,
  display_name text not null,
  role text not null check (role in ('parent', 'child')),
  created_at timestamptz not null default now()
);

create table if not exists public.families (
  id uuid primary key default gen_random_uuid(),
  family_name text not null,
  family_code text not null unique,
  parent_pin_hash text,
  parent_pin_enabled boolean not null default true,
  created_at timestamptz not null default now()
);

create table if not exists public.family_members (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references public.families(id) on delete cascade,
  profile_id uuid references public.profiles(id) on delete cascade,
  role text not null check (role in ('parent', 'child')),
  created_at timestamptz not null default now(),
  unique (family_id, profile_id)
);

create table if not exists public.devices (
  id uuid primary key default gen_random_uuid(),
  family_id uuid references public.families(id) on delete cascade,
  owner_child_id uuid references public.profiles(id) on delete set null,
  device_id text not null unique,
  device_name text not null,
  device_model text not null,
  remaining_seconds bigint not null default 0 check (remaining_seconds >= 0),
  used_seconds bigint not null default 0 check (used_seconds >= 0),
  locked boolean not null default false,
  lock_reason text,
  battery_level integer check (battery_level between 0 and 100),
  last_seen timestamptz,
  created_at timestamptz not null default now()
);

create table if not exists public.commands (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references public.devices(id) on delete cascade,
  command text not null check (
    command in (
      'ADD_TIME',
      'DEDUCT_TIME',
      'SET_TIME',
      'LOCK',
      'UNLOCK',
      'UPDATE_PIN',
      'TASK_ASSIGNED',
      'TASK_SUBMITTED',
      'TASK_APPROVED',
      'TASK_REJECTED',
      'TASK_DELETED'
    )
  ),
  value bigint,
  remark text,
  status text not null default 'pending' check (status in ('pending', 'processing', 'processed', 'failed')),
  created_by uuid references public.profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  processed_at timestamptz,
  error_message text
);

create table if not exists public.device_usage_events (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references public.devices(id) on delete cascade,
  client_event_id text not null,
  delta_seconds bigint not null check (delta_seconds > 0),
  remaining_seconds_after bigint not null check (remaining_seconds_after >= 0),
  started_at timestamptz not null default now(),
  ended_at timestamptz not null default now(),
  synced_at timestamptz not null default now(),
  unique (device_id, client_event_id)
);

create table if not exists public.messages (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references public.families(id) on delete cascade,
  sender_id uuid references public.profiles(id) on delete set null,
  receiver_id uuid references public.profiles(id) on delete set null,
  content text not null,
  is_read boolean not null default false,
  created_at timestamptz not null default now()
);

create table if not exists public.tasks (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references public.families(id) on delete cascade,
  title text not null,
  description text,
  reward_seconds bigint not null default 0 check (reward_seconds >= 0),
  assigned_device_id uuid references public.devices(id) on delete set null,
  due_date timestamptz,
  status text not null default 'open' check (status in ('open', 'submitted', 'approved', 'rejected', 'closed')),
  created_by uuid references public.profiles(id) on delete set null,
  created_at timestamptz not null default now()
);

create table if not exists public.task_submissions (
  id uuid primary key default gen_random_uuid(),
  task_id uuid not null references public.tasks(id) on delete cascade,
  child_id uuid references public.profiles(id) on delete set null,
  photo_url text,
  comment text,
  status text not null default 'submitted' check (status in ('submitted', 'approved', 'rejected')),
  submitted_at timestamptz not null default now()
);

create table if not exists public.audit_logs (
  id uuid primary key default gen_random_uuid(),
  family_id uuid references public.families(id) on delete cascade,
  operator_id uuid references public.profiles(id) on delete set null,
  target_device_id uuid references public.devices(id) on delete set null,
  action text not null,
  old_value jsonb,
  new_value jsonb,
  remark text,
  device_local_time timestamptz,
  created_at timestamptz not null default now()
);

create index if not exists idx_devices_family_id on public.devices(family_id);
create index if not exists idx_commands_device_status on public.commands(device_id, status, created_at);
create index if not exists idx_usage_events_device_time on public.device_usage_events(device_id, started_at);
create index if not exists idx_audit_logs_family_time on public.audit_logs(family_id, created_at desc);

create or replace function public.bind_family_by_code(p_family_code text)
returns table (
  id uuid,
  family_name text,
  family_code text
)
language plpgsql
security definer
set search_path = public
as $$
declare
  matched_family public.families%rowtype;
  existing_family public.families%rowtype;
begin
  if auth.uid() is null then
    raise exception 'Authentication required';
  end if;

  select f.*
  into existing_family
  from public.family_members fm
  join public.families f on f.id = fm.family_id
  where fm.profile_id = auth.uid()
    and fm.role = 'parent'
  order by f.created_at asc
  limit 1;

  select *
  into matched_family
  from public.families
  where families.family_code = upper(trim(p_family_code))
  limit 1;

  if matched_family.id is null then
    return;
  end if;

  if existing_family.id is not null then
    if existing_family.id <> matched_family.id then
      raise exception 'parent_already_has_family';
    end if;

    id := existing_family.id;
    family_name := existing_family.family_name;
    family_code := existing_family.family_code;
    return next;
    return;
  end if;

  insert into public.family_members (family_id, profile_id, role)
  values (matched_family.id, auth.uid(), 'parent')
  on conflict (family_id, profile_id) do nothing;

  id := matched_family.id;
  family_name := matched_family.family_name;
  family_code := matched_family.family_code;
  return next;
end;
$$;

revoke all on function public.bind_family_by_code(text) from public;
grant execute on function public.bind_family_by_code(text) to authenticated;

create or replace function public.create_family_with_code(
  p_family_name text,
  p_family_code text,
  p_parent_pin_hash text
)
returns table (
  id uuid,
  family_name text,
  family_code text
)
language plpgsql
security definer
set search_path = public
as $$
declare
  existing_family public.families%rowtype;
  created_family public.families%rowtype;
begin
  if auth.uid() is null then
    raise exception 'Authentication required';
  end if;

  select f.*
  into existing_family
  from public.family_members fm
  join public.families f on f.id = fm.family_id
  where fm.profile_id = auth.uid()
    and fm.role = 'parent'
  order by f.created_at asc
  limit 1;

  if existing_family.id is not null then
    id := existing_family.id;
    family_name := existing_family.family_name;
    family_code := existing_family.family_code;
    return next;
    return;
  end if;

  insert into public.families (
    family_name,
    family_code,
    parent_pin_hash,
    parent_pin_enabled
  )
  values (
    trim(p_family_name),
    upper(trim(p_family_code)),
    p_parent_pin_hash,
    true
  )
  returning * into created_family;

  insert into public.family_members (family_id, profile_id, role)
  values (created_family.id, auth.uid(), 'parent')
  on conflict (family_id, profile_id) do nothing;

  id := created_family.id;
  family_name := created_family.family_name;
  family_code := created_family.family_code;
  return next;
end;
$$;

revoke all on function public.create_family_with_code(text, text, text) from public;
grant execute on function public.create_family_with_code(text, text, text) to authenticated;

alter table public.profiles enable row level security;
alter table public.families enable row level security;
alter table public.family_members enable row level security;
alter table public.devices enable row level security;
alter table public.commands enable row level security;
alter table public.device_usage_events enable row level security;
alter table public.messages enable row level security;
alter table public.tasks enable row level security;
alter table public.task_submissions enable row level security;
alter table public.audit_logs enable row level security;

drop policy if exists "dev anon all devices" on public.devices;
drop policy if exists "dev anon all commands" on public.commands;
drop policy if exists "dev anon all families" on public.families;
drop policy if exists "dev anon all family members" on public.family_members;
drop policy if exists "dev anon all usage events" on public.device_usage_events;
drop policy if exists "dev anon all audit logs" on public.audit_logs;

create policy "dev anon all devices"
on public.devices
for all
to anon
using (true)
with check (true);

create policy "dev anon all commands"
on public.commands
for all
to anon
using (true)
with check (true);

create policy "dev anon all families"
on public.families
for all
to anon
using (true)
with check (true);

create policy "dev anon all family members"
on public.family_members
for all
to anon
using (true)
with check (true);

create policy "dev anon all usage events"
on public.device_usage_events
for all
to anon
using (true)
with check (true);

create policy "dev anon all audit logs"
on public.audit_logs
for all
to anon
using (true)
with check (true);

do $$
begin
  begin
    alter publication supabase_realtime add table public.commands;
  exception
    when duplicate_object then null;
  end;

  begin
    alter publication supabase_realtime add table public.devices;
  exception
    when duplicate_object then null;
  end;

  begin
    alter publication supabase_realtime add table public.messages;
  exception
    when duplicate_object then null;
  end;

  begin
    alter publication supabase_realtime add table public.tasks;
  exception
    when duplicate_object then null;
  end;
end $$;

-- =============================================================================
-- END docs\SUPABASE_SETUP_DEV.sql
-- =============================================================================


-- =============================================================================
-- BEGIN docs\SUPABASE_DROP_CHILD_PIN.sql
-- =============================================================================

-- Family Time Manager ??drop Child PIN.
-- The Child PIN feature (enable/disable/reset, child unlock-by-PIN) is removed from scope.
-- Run this on an existing Supabase project to clean up the leftover schema. Safe and idempotent.
--
-- Kept on purpose (these are Parent PIN, not Child PIN):
--   - families.parent_pin_hash / families.parent_pin_enabled
--   - the UPDATE_PIN action (used by the Parent PIN audit trail)

-- 1. Drop the child-PIN columns on devices.
alter table public.devices drop column if exists pin_enabled;
alter table public.devices drop column if exists child_pin_hash;

-- 2. Tighten the commands command whitelist (remove child-PIN enable/disable).
--    Inline CHECK constraints are auto-named "<table>_<column>_check".
alter table public.commands drop constraint if exists commands_command_check;
alter table public.commands
  add constraint commands_command_check
  check (
    command in (
      'ADD_TIME',
      'DEDUCT_TIME',
      'SET_TIME',
      'LOCK',
      'UNLOCK',
      'UPDATE_PIN',
      'SET_DAILY_WALLET_RULE',
      'TASK_ASSIGNED',
      'TASK_SUBMITTED',
      'TASK_APPROVED',
      'TASK_REJECTED',
      'TASK_DELETED'
    )
  );

-- 3. Tighten the audit_logs action whitelist if it exists (base schema only; the dev setup
--    leaves action unconstrained, in which case this drop is a no-op).
alter table public.audit_logs drop constraint if exists audit_logs_action_check;
alter table public.audit_logs
  add constraint audit_logs_action_check
  check (
    action in (
      'ADD_TIME',
      'DEDUCT_TIME',
      'SET_TIME',
      'LOCK',
      'UNLOCK',
      'UPDATE_PIN',
      'UPDATE_DAILY_WALLET_RULE',
      'SYNC_DEVICE_STATE',
      'DAILY_WALLET_RULE',
      'PARENT_MODE_PIN_SUCCESS',
      'PARENT_MODE_PIN_FAILED',
      'SEND_MESSAGE',
      'CREATE_TASK',
      'DELETE_TASK',
      'APPROVE_TASK',
      'REJECT_TASK',
      'CREATE_PRODUCT',
      'DELETE_PRODUCT',
      'REMOTE_ADD_TIME',
      'REMOTE_DEDUCT_TIME',
      'REMOTE_SET_TIME',
      'REMOTE_LOCK',
      'REMOTE_UNLOCK',
      'REMOTE_SET_DAILY_WALLET_RULE',
      'UPDATE_TREASURE_CHEST',
      'TREASURE_CHEST_OPENED',
      'ADD_PARENT',
      'REMOVE_PARENT'
    )
  );

-- =============================================================================
-- END docs\SUPABASE_DROP_CHILD_PIN.sql
-- =============================================================================


-- =============================================================================
-- BEGIN docs\SUPABASE_OWNER_FAMILY_DELETE.sql
-- =============================================================================

-- Family owner / soft-delete patch.
-- Run after the current schema/RLS/RPC files.
--
-- Effects:
-- 1. Families are soft-deleted with families.deleted_at.
-- 2. Devices are soft-removed with devices.removed_at.
-- 3. The creator of a family is stored as family_members.role = 'owner'.
-- 4. Only the owner can soft-delete the family through delete_family_as_owner().

alter table public.families
  add column if not exists deleted_at timestamptz;

alter table public.devices
  add column if not exists removed_at timestamptz;

alter table public.family_members
  drop constraint if exists family_members_role_check;

alter table public.family_members
  add constraint family_members_role_check
  check (role in ('owner', 'parent', 'child'));

update public.family_members fm
set role = 'owner'
where fm.role = 'parent'
  and fm.created_at = (
    select min(fm2.created_at)
    from public.family_members fm2
    where fm2.family_id = fm.family_id
      and fm2.role in ('owner', 'parent')
  );

create or replace function public.is_family_owner(p_family_id uuid)
returns boolean
language sql
security definer
set search_path = public
stable
as $$
  select exists (
    select 1
    from public.family_members fm
    where fm.family_id = p_family_id
      and fm.profile_id = auth.uid()
      and fm.role = 'owner'
  );
$$;

create or replace function public.is_family_parent(p_family_id uuid)
returns boolean
language sql
security definer
set search_path = public
stable
as $$
  select exists (
    select 1
    from public.family_members fm
    where fm.family_id = p_family_id
      and fm.profile_id = auth.uid()
      and fm.role in ('owner', 'parent')
  );
$$;

revoke all on function public.is_family_owner(uuid) from public;
grant execute on function public.is_family_owner(uuid) to authenticated;

create or replace function public.create_family_with_code(
  p_family_name text,
  p_family_code text,
  p_parent_pin_hash text
)
returns table (
  id uuid,
  family_name text,
  family_code text
)
language plpgsql
security definer
set search_path = public
as $$
declare
  existing_family public.families%rowtype;
  created_family public.families%rowtype;
begin
  if auth.uid() is null then
    raise exception 'Authentication required';
  end if;

  select f.*
  into existing_family
  from public.family_members fm
  join public.families f on f.id = fm.family_id
  where fm.profile_id = auth.uid()
    and fm.role in ('owner', 'parent')
    and f.deleted_at is null
  order by f.created_at asc
  limit 1;

  if existing_family.id is not null then
    id := existing_family.id;
    family_name := existing_family.family_name;
    family_code := existing_family.family_code;
    return next;
    return;
  end if;

  insert into public.families (
    family_name,
    family_code,
    parent_pin_hash,
    parent_pin_enabled
  )
  values (
    trim(p_family_name),
    upper(trim(p_family_code)),
    p_parent_pin_hash,
    true
  )
  returning * into created_family;

  insert into public.family_members (family_id, profile_id, role)
  values (created_family.id, auth.uid(), 'owner')
  on conflict (family_id, profile_id) do update set role = 'owner';

  id := created_family.id;
  family_name := created_family.family_name;
  family_code := created_family.family_code;
  return next;
end;
$$;

revoke all on function public.create_family_with_code(text, text, text) from public;
grant execute on function public.create_family_with_code(text, text, text) to authenticated;

create or replace function public.bind_family_by_code(p_family_code text)
returns table (
  id uuid,
  family_name text,
  family_code text
)
language plpgsql
security definer
set search_path = public
as $$
declare
  target_family public.families%rowtype;
  existing_family public.families%rowtype;
begin
  if auth.uid() is null then
    raise exception 'Authentication required';
  end if;

  select f.*
  into existing_family
  from public.family_members fm
  join public.families f on f.id = fm.family_id
  where fm.profile_id = auth.uid()
    and fm.role in ('owner', 'parent')
    and f.deleted_at is null
  order by f.created_at asc
  limit 1;

  select *
  into target_family
  from public.families
  where family_code = upper(trim(p_family_code))
    and deleted_at is null
  limit 1;

  if target_family.id is null then
    raise exception 'family_not_found';
  end if;

  if existing_family.id is not null and existing_family.id <> target_family.id then
    raise exception 'parent_already_has_family';
  end if;

  insert into public.family_members (family_id, profile_id, role)
  values (target_family.id, auth.uid(), 'parent')
  on conflict (family_id, profile_id) do update
    set role = case
      when public.family_members.role = 'owner' then 'owner'
      else 'parent'
    end;

  id := target_family.id;
  family_name := target_family.family_name;
  family_code := target_family.family_code;
  return next;
end;
$$;

revoke all on function public.bind_family_by_code(text) from public;
grant execute on function public.bind_family_by_code(text) to authenticated;

create or replace function public.delete_family_as_owner(p_family_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if auth.uid() is null then
    raise exception 'Authentication required';
  end if;

  if not public.is_family_owner(p_family_id) then
    raise exception 'owner_required';
  end if;

  update public.families
  set deleted_at = now()
  where id = p_family_id
    and deleted_at is null;

  update public.devices
  set removed_at = coalesce(removed_at, now()),
      locked = false
  where family_id = p_family_id
    and removed_at is null;
end;
$$;

revoke all on function public.delete_family_as_owner(uuid) from public;
grant execute on function public.delete_family_as_owner(uuid) to authenticated;

-- =============================================================================
-- END docs\SUPABASE_OWNER_FAMILY_DELETE.sql
-- =============================================================================


-- =============================================================================
-- BEGIN docs\SUPABASE_PRODUCTION_RLS.sql
-- =============================================================================

-- =============================================================================
-- Family Time Manager ??PRODUCTION Row Level Security
-- =============================================================================
-- This is the finalized, apply-ready replacement for the development anon-all
-- policies created by SUPABASE_SETUP_DEV.sql / SUPABASE_DEV_FAMILY_POLICY_PATCH.sql.
-- It supersedes SUPABASE_PRODUCTION_RLS_DRAFT.sql.
--
-- The script is idempotent: it drops the dev policies and any earlier copy of
-- these production policies/helpers before recreating them, so it can be re-run.
--
-- HOW TO RUN
--   Supabase Dashboard -> SQL Editor -> New query -> paste this whole file -> Run.
--   Run AFTER the base schema and both RPCs exist (SUPABASE_SETUP_DEV.sql already
--   creates them; otherwise run SUPABASE_CREATE_FAMILY_RPC.sql and
--   SUPABASE_BIND_FAMILY_RPC.sql first).
--
-- -----------------------------------------------------------------------------
-- !! PREREQUISITE / BLOCKER ??READ BEFORE APPLYING ON A LIVE PROJECT !!
-- -----------------------------------------------------------------------------
-- These policies grant access to the role `authenticated` only. There are NO
-- `anon` policies, so once this runs every request that is NOT carrying a valid
-- signed-in user JWT for a family member is denied.
--
-- The app sends the parent's Supabase access token when one is stored, and otherwise
-- falls back to the anon/publishable key (see SupabaseRestAuthHeaders). For these
-- policies to work continuously:
--
--   1. Token refresh ??DONE. SupabaseAuthClient.refreshIfNeeded() proactively
--      refreshes via POST /auth/v1/token?grant_type=refresh_token (stored expiry +
--      120s margin) and is called from FamilySyncWorker and the foreground service
--      tick, so the access token no longer lapses to the anon key after ~1 hour.
--
--   2. Child devices need a persistent family-member session. A child/secondary
--      device must be signed in as a family member (the parent signs in on that
--      device to bind it). That session is now kept fresh by #1 in both the
--      foreground service and FamilySyncWorker. Make sure each child device is
--      signed in before relying on production RLS.
--
-- Family and membership creation do NOT need INSERT policies here: they go through
-- the SECURITY DEFINER RPCs create_family_with_code / bind_family_by_code, which
-- run with definer privileges and bypass RLS.
-- =============================================================================


-- 1. Enable RLS on every table -------------------------------------------------
alter table public.profiles            enable row level security;
alter table public.families            enable row level security;
alter table public.family_members      enable row level security;
alter table public.devices             enable row level security;
alter table public.commands            enable row level security;
alter table public.device_usage_events enable row level security;
alter table public.messages            enable row level security;
alter table public.tasks               enable row level security;
alter table public.task_submissions    enable row level security;
alter table public.audit_logs          enable row level security;


-- 2. Membership helper functions ----------------------------------------------
-- SECURITY DEFINER so they read family_members without re-triggering RLS. This is
-- the recommended Supabase pattern and prevents policy recursion.
create or replace function public.is_family_member(p_family_id uuid)
returns boolean
language sql
security definer
set search_path = public
stable
as $$
  select exists (
    select 1
    from public.family_members fm
    where fm.family_id = p_family_id
      and fm.profile_id = auth.uid()
  );
$$;

create or replace function public.is_family_parent(p_family_id uuid)
returns boolean
language sql
security definer
set search_path = public
stable
as $$
  select exists (
    select 1
    from public.family_members fm
    where fm.family_id = p_family_id
      and fm.profile_id = auth.uid()
      -- Include 'owner' (the family creator): create_family_with_code inserts the
      -- creator as 'owner', and the families UPDATE policy relies on this helper to
      -- let them sync the shared Parent PIN. Excluding 'owner' here made the PIN
      -- PATCH match zero rows under RLS while PostgREST still returned success.
      and fm.role in ('owner', 'parent')
  );
$$;

-- Owner check: only the family creator (role 'owner') passes. Used to restrict
-- changing the shared Parent PIN to the owner; other parents (role 'parent',
-- e.g. bound by family code) can manage tasks/store/etc. but cannot change the PIN.
create or replace function public.is_family_owner(p_family_id uuid)
returns boolean
language sql
security definer
set search_path = public
stable
as $$
  select exists (
    select 1
    from public.family_members fm
    where fm.family_id = p_family_id
      and fm.profile_id = auth.uid()
      and fm.role = 'owner'
  );
$$;

revoke all on function public.is_family_member(uuid) from public;
revoke all on function public.is_family_parent(uuid) from public;
revoke all on function public.is_family_owner(uuid) from public;
grant execute on function public.is_family_member(uuid) to authenticated;
grant execute on function public.is_family_parent(uuid) to authenticated;
grant execute on function public.is_family_owner(uuid) to authenticated;


-- 3. Drop development anon-all policies ----------------------------------------
drop policy if exists "dev anon all devices"        on public.devices;
drop policy if exists "dev anon all commands"       on public.commands;
drop policy if exists "dev anon all families"       on public.families;
drop policy if exists "dev anon all family members" on public.family_members;
drop policy if exists "dev anon all usage events"   on public.device_usage_events;
drop policy if exists "dev anon all audit logs"     on public.audit_logs;


-- 4. Drop any earlier copy of these production policies (idempotent re-run) -----
drop policy if exists "parents manage own profile"          on public.profiles;
drop policy if exists "family members read families"        on public.families;
drop policy if exists "parents update families"             on public.families;
drop policy if exists "owner updates families"              on public.families;
drop policy if exists "family members manage families"      on public.families;
drop policy if exists "members read own membership"         on public.family_members;
drop policy if exists "parents create own family membership" on public.family_members;
drop policy if exists "family members manage devices"       on public.devices;
drop policy if exists "family members manage commands"      on public.commands;
drop policy if exists "family members manage usage events"  on public.device_usage_events;
drop policy if exists "family members manage messages"      on public.messages;
drop policy if exists "family members manage tasks"         on public.tasks;
drop policy if exists "family members manage submissions"   on public.task_submissions;
drop policy if exists "family members read audit logs"      on public.audit_logs;
drop policy if exists "family members insert audit logs"    on public.audit_logs;


-- 5. profiles ------------------------------------------------------------------
-- A signed-in user can read and write only their own profile row. Covers the
-- merge-duplicates upsert the app performs on sign-in.
create policy "parents manage own profile"
on public.profiles
for all
to authenticated
using (id = auth.uid())
with check (id = auth.uid());


-- 6. families ------------------------------------------------------------------
-- Read: any member of the family. Update: ONLY the family owner (e.g. syncing the
-- shared Parent PIN hash). Other parents bound by family code cannot change the PIN.
-- Insert/bind happen through the SECURITY DEFINER RPCs.
create policy "family members read families"
on public.families
for select
to authenticated
using (public.is_family_member(id));

create policy "owner updates families"
on public.families
for update
to authenticated
using (public.is_family_owner(id))
with check (public.is_family_owner(id));


-- 7. family_members ------------------------------------------------------------
-- Read: a user can see their own membership rows (this is what the helper
-- functions rely on conceptually, and what the app needs to resolve its family).
-- Insert: a user may add only themselves, as a parent. (RPCs also cover this.)
create policy "members read own membership"
on public.family_members
for select
to authenticated
using (profile_id = auth.uid());

create policy "parents create own family membership"
on public.family_members
for insert
to authenticated
with check (
  profile_id = auth.uid()
  and role = 'parent'
);


-- 8. devices -------------------------------------------------------------------
-- Any member of the device's family may read/insert/update/delete it. The app
-- sets devices.family_id at insert time when a family is bound, so the WITH CHECK
-- passes for the binding parent.
create policy "family members manage devices"
on public.devices
for all
to authenticated
using (public.is_family_member(family_id))
with check (public.is_family_member(family_id));


-- 9. commands ------------------------------------------------------------------
-- Access is scoped through the command's device to that device's family.
create policy "family members manage commands"
on public.commands
for all
to authenticated
using (
  exists (
    select 1 from public.devices d
    where d.id = commands.device_id
      and public.is_family_member(d.family_id)
  )
)
with check (
  exists (
    select 1 from public.devices d
    where d.id = commands.device_id
      and public.is_family_member(d.family_id)
  )
);


-- 10. device_usage_events ------------------------------------------------------
create policy "family members manage usage events"
on public.device_usage_events
for all
to authenticated
using (
  exists (
    select 1 from public.devices d
    where d.id = device_usage_events.device_id
      and public.is_family_member(d.family_id)
  )
)
with check (
  exists (
    select 1 from public.devices d
    where d.id = device_usage_events.device_id
      and public.is_family_member(d.family_id)
  )
);


-- 11. messages -----------------------------------------------------------------
-- Family-scoped. Sufficient for the planned parent<->child messaging feature.
create policy "family members manage messages"
on public.messages
for all
to authenticated
using (public.is_family_member(family_id))
with check (public.is_family_member(family_id));


-- 12. tasks --------------------------------------------------------------------
create policy "family members manage tasks"
on public.tasks
for all
to authenticated
using (public.is_family_member(family_id))
with check (public.is_family_member(family_id));


-- 13. task_submissions ---------------------------------------------------------
-- Scoped through the parent task to that task's family.
create policy "family members manage submissions"
on public.task_submissions
for all
to authenticated
using (
  exists (
    select 1 from public.tasks t
    where t.id = task_submissions.task_id
      and public.is_family_member(t.family_id)
  )
)
with check (
  exists (
    select 1 from public.tasks t
    where t.id = task_submissions.task_id
      and public.is_family_member(t.family_id)
  )
);


-- 14. audit_logs ---------------------------------------------------------------
-- Members may read their family's audit log and insert new entries. No update or
-- delete policy: audit rows are append-only.
create policy "family members read audit logs"
on public.audit_logs
for select
to authenticated
using (public.is_family_member(family_id));

create policy "family members insert audit logs"
on public.audit_logs
for insert
to authenticated
with check (public.is_family_member(family_id));


-- =============================================================================
-- Post-apply sanity check (optional): list active policies per table.
--   select schemaname, tablename, policyname, roles, cmd
--   from pg_policies
--   where schemaname = 'public'
--   order by tablename, policyname;
-- Expect: no policy with roles = {anon}; every table above has its named policy.
-- =============================================================================

-- =============================================================================
-- END docs\SUPABASE_PRODUCTION_RLS.sql
-- =============================================================================


-- =============================================================================
-- BEGIN docs\SUPABASE_CHILD_DEVICE_CONNECT_RPC.sql
-- =============================================================================

-- Child device connection by family code.
-- Run after SUPABASE_OWNER_FAMILY_DELETE.sql.
--
-- This lets a child device connect without creating a child account. The family
-- code acts as the shared setup code. Existing connected child devices should
-- not be changed from the child UI; use Parent PIN / parent mode to reconnect.

alter table public.devices
  add column if not exists daily_wallet_mode text not null default 'NONE';

alter table public.devices
  add column if not exists daily_wallet_amount_seconds bigint not null default 0
  check (daily_wallet_amount_seconds >= 0);

alter table public.devices
  add column if not exists daily_wallet_last_applied_date text not null default '';

create or replace function public.connect_child_device_by_family_code(
  p_family_code text,
  p_device_uuid text,
  p_device_name text,
  p_device_model text
)
returns table (
  family_id uuid,
  family_name text,
  family_code text,
  device_id uuid
)
language plpgsql
security definer
set search_path = public
as $$
declare
  target_family public.families%rowtype;
  target_device public.devices%rowtype;
begin
  select *
  into target_family
  from public.families f
  where f.family_code = upper(trim(p_family_code))
    and f.deleted_at is null
  limit 1;

  if target_family.id is null then
    raise exception 'family_not_found';
  end if;

  select *
  into target_device
  from public.devices d
  where d.device_id = trim(p_device_uuid)
  limit 1;

  if target_device.id is null then
    insert into public.devices (
      family_id,
      device_id,
      device_name,
      device_model,
      remaining_seconds,
      used_seconds,
      locked,
      removed_at
    )
    values (
      target_family.id,
      trim(p_device_uuid),
      coalesce(nullif(trim(p_device_name), ''), 'Child device'),
      coalesce(nullif(trim(p_device_model), ''), 'Android device'),
      0,
      0,
      false,
      null
    )
    returning * into target_device;
  else
    update public.devices d
    set family_id = target_family.id,
        device_name = coalesce(nullif(trim(p_device_name), ''), d.device_name),
        device_model = coalesce(nullif(trim(p_device_model), ''), d.device_model),
        remaining_seconds = 0,
        used_seconds = 0,
        locked = false,
        removed_at = null,
        daily_wallet_mode = 'NONE',
        daily_wallet_amount_seconds = 0,
        daily_wallet_last_applied_date = ''
    where d.id = target_device.id
    returning * into target_device;
  end if;

  family_id := target_family.id;
  family_name := target_family.family_name;
  family_code := target_family.family_code;
  device_id := target_device.id;
  return next;
end;
$$;

revoke all on function public.connect_child_device_by_family_code(text, text, text, text) from public;
grant execute on function public.connect_child_device_by_family_code(text, text, text, text) to anon;
grant execute on function public.connect_child_device_by_family_code(text, text, text, text) to authenticated;

-- =============================================================================
-- END docs\SUPABASE_CHILD_DEVICE_CONNECT_RPC.sql
-- =============================================================================


-- =============================================================================
-- BEGIN docs\SUPABASE_CHILD_DEVICE_SYNC_RPC.sql
-- =============================================================================

-- Child-device sync RPCs for no-login child devices.
-- Run after SUPABASE_OWNER_FAMILY_DELETE.sql.
--
-- Production RLS blocks anonymous direct table access. These SECURITY DEFINER
-- RPCs scope every operation by the child device id, so child devices can pull
-- commands and sync state without a Supabase Auth user.

alter table public.devices
  add column if not exists daily_wallet_mode text not null default 'NONE';

alter table public.devices
  add column if not exists daily_wallet_amount_seconds bigint not null default 0
  check (daily_wallet_amount_seconds >= 0);

alter table public.devices
  add column if not exists daily_wallet_last_applied_date text not null default '';

alter table public.devices
  drop constraint if exists devices_daily_wallet_mode_check;

alter table public.devices
  add constraint devices_daily_wallet_mode_check
  check (daily_wallet_mode in ('NONE', 'ADD', 'ZERO', 'RESET'));


-- =============================================================================
-- TREASURE CHEST GAME
-- =============================================================================

alter table public.devices
  add column if not exists treasure_chest_enabled boolean not null default false;

alter table public.devices
  add column if not exists treasure_chest_daily_limit integer not null default 3
  check (treasure_chest_daily_limit between 1 and 20);

alter table public.devices
  add column if not exists treasure_chest_allow_over_daily_target boolean not null default true;

create table if not exists public.treasure_chest_plays (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references public.families(id) on delete cascade,
  device_id uuid not null references public.devices(id) on delete cascade,
  cost_seconds bigint not null default 900,
  reward_seconds bigint not null,
  remaining_seconds_before bigint not null,
  remaining_seconds_after bigint not null,
  played_on date not null default ((now() at time zone 'Asia/Taipei')::date),
  created_at timestamptz not null default now()
);

create index if not exists idx_treasure_chest_plays_device_day
  on public.treasure_chest_plays(device_id, played_on);

alter table public.treasure_chest_plays enable row level security;

drop policy if exists "family parents read treasure chest plays" on public.treasure_chest_plays;
create policy "family parents read treasure chest plays"
on public.treasure_chest_plays
for select
to authenticated
using (public.is_family_parent(family_id));

create or replace function public.get_treasure_chest_state(p_device_id uuid)
returns table (
  enabled boolean,
  daily_limit integer,
  plays_today integer,
  allow_over_daily_target boolean,
  remaining_seconds bigint
)
language plpgsql
security definer
set search_path = public
as $$
begin
  return query
  select
    d.treasure_chest_enabled,
    d.treasure_chest_daily_limit,
    (
      select count(*)::integer
      from public.treasure_chest_plays p
      where p.device_id = d.id
        and p.played_on = (now() at time zone 'Asia/Taipei')::date
    ),
    d.treasure_chest_allow_over_daily_target,
    d.remaining_seconds
  from public.devices d
  where d.id = p_device_id
    and d.removed_at is null;
end;
$$;

revoke all on function public.get_treasure_chest_state(uuid) from public;
grant execute on function public.get_treasure_chest_state(uuid) to anon, authenticated;

create or replace function public.open_treasure_chest(p_device_id uuid)
returns table (
  reward_minutes integer,
  remaining_seconds bigint,
  plays_today integer,
  daily_limit integer
)
language plpgsql
security definer
set search_path = public
as $$
declare
  target_device public.devices%rowtype;
  today_date date := (now() at time zone 'Asia/Taipei')::date;
  today_count integer;
  rewards integer[] := array[3, 5, 8, 15, 25];
  selected_reward integer;
  next_remaining bigint;
  daily_target bigint;
begin
  select *
  into target_device
  from public.devices
  where id = p_device_id
    and removed_at is null
  for update;

  if target_device.id is null then
    raise exception 'device_not_found';
  end if;

  if not target_device.treasure_chest_enabled then
    raise exception 'treasure_chest_disabled';
  end if;

  select count(*)::integer
  into today_count
  from public.treasure_chest_plays
  where device_id = p_device_id
    and played_on = today_date;

  if today_count >= target_device.treasure_chest_daily_limit then
    raise exception 'treasure_chest_daily_limit_reached';
  end if;

  if target_device.remaining_seconds < 900 then
    raise exception 'treasure_chest_insufficient_time';
  end if;

  selected_reward := rewards[1 + floor(random() * array_length(rewards, 1))::integer];
  next_remaining := target_device.remaining_seconds - 900 + (selected_reward * 60);

  if not target_device.treasure_chest_allow_over_daily_target then
    daily_target := target_device.daily_wallet_amount_seconds;
    if daily_target > 0 then
      next_remaining := least(next_remaining, daily_target);
    end if;
  end if;

  update public.devices
  set remaining_seconds = greatest(0, next_remaining),
      locked = greatest(0, next_remaining) <= 0,
      last_seen = now()
  where id = p_device_id;

  insert into public.treasure_chest_plays (
    family_id,
    device_id,
    cost_seconds,
    reward_seconds,
    remaining_seconds_before,
    remaining_seconds_after,
    played_on
  )
  values (
    target_device.family_id,
    target_device.id,
    900,
    selected_reward * 60,
    target_device.remaining_seconds,
    greatest(0, next_remaining),
    today_date
  );

  insert into public.audit_logs (
    family_id,
    target_device_id,
    action,
    old_value,
    new_value,
    remark,
    device_local_time
  )
  values (
    target_device.family_id,
    target_device.id,
    'TREASURE_CHEST_OPENED',
    jsonb_build_object('remaining_seconds', target_device.remaining_seconds),
    jsonb_build_object(
      'cost_minutes', 15,
      'reward_minutes', selected_reward,
      'remaining_seconds', greatest(0, next_remaining)
    ),
    'Treasure chest opened: cost 15; reward ' || selected_reward,
    now()
  );

  reward_minutes := selected_reward;
  remaining_seconds := greatest(0, next_remaining);
  plays_today := today_count + 1;
  daily_limit := target_device.treasure_chest_daily_limit;
  return next;
end;
$$;

revoke all on function public.open_treasure_chest(uuid) from public;
grant execute on function public.open_treasure_chest(uuid) to anon, authenticated;

create or replace function public.list_pending_commands_for_device(p_device_id uuid)
returns table (
  id uuid,
  command text,
  value bigint,
  remark text,
  created_at timestamptz
)
language plpgsql
security definer
set search_path = public
as $$
begin
  if not exists (
    select 1
    from public.devices d
    join public.families f on f.id = d.family_id
    where d.id = p_device_id
      and d.removed_at is null
      and f.deleted_at is null
  ) then
    return;
  end if;

  return query
  select c.id, c.command, c.value, c.remark, c.created_at
  from public.commands c
  where c.device_id = p_device_id
    and c.status = 'pending'
  order by c.created_at asc;
end;
$$;

revoke all on function public.list_pending_commands_for_device(uuid) from public;
grant execute on function public.list_pending_commands_for_device(uuid) to anon, authenticated;

create or replace function public.mark_device_command_processed(
  p_command_id uuid,
  p_device_id uuid
)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  update public.commands
  set status = 'processed',
      processed_at = now()
  where id = p_command_id
    and device_id = p_device_id
    and status = 'pending';
end;
$$;

revoke all on function public.mark_device_command_processed(uuid, uuid) from public;
grant execute on function public.mark_device_command_processed(uuid, uuid) to anon, authenticated;

create or replace function public.get_child_device_state(p_device_id uuid)
returns table (
  remaining_seconds bigint,
  used_seconds bigint,
  locked boolean,
  daily_wallet_amount_seconds bigint,
  daily_wallet_mode text,
  daily_wallet_last_applied_date text,
  family_id uuid,
  removed_at timestamptz,
  deleted_at timestamptz
)
language plpgsql
security definer
set search_path = public
as $$
begin
  return query
  select
    d.remaining_seconds,
    d.used_seconds,
    d.locked,
    d.daily_wallet_amount_seconds,
    d.daily_wallet_mode,
    d.daily_wallet_last_applied_date,
    d.family_id,
    d.removed_at,
    f.deleted_at
  from public.devices d
  join public.families f on f.id = d.family_id
  where d.id = p_device_id
  limit 1;
end;
$$;

revoke all on function public.get_child_device_state(uuid) from public;
grant execute on function public.get_child_device_state(uuid) to anon, authenticated;

create or replace function public.update_child_device_state(
  p_device_id uuid,
  p_remaining_seconds bigint,
  p_used_seconds bigint,
  p_locked boolean,
  p_daily_wallet_amount_seconds bigint,
  p_daily_wallet_mode text,
  p_daily_wallet_last_applied_date text
)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  update public.devices d
  set remaining_seconds = greatest(0, p_remaining_seconds),
      used_seconds = greatest(0, p_used_seconds),
      locked = p_locked,
      daily_wallet_amount_seconds = greatest(0, p_daily_wallet_amount_seconds),
      daily_wallet_mode = case
        when p_daily_wallet_mode in ('NONE', 'ADD', 'ZERO', 'RESET')
          then p_daily_wallet_mode
        else 'NONE'
      end,
      daily_wallet_last_applied_date = coalesce(p_daily_wallet_last_applied_date, ''),
      last_seen = now()
  where d.id = p_device_id
    and d.removed_at is null
    and exists (
      select 1 from public.families f
      where f.id = d.family_id
        and f.deleted_at is null
    );
end;
$$;

revoke all on function public.update_child_device_state(uuid, bigint, bigint, boolean, bigint, text, text) from public;
grant execute on function public.update_child_device_state(uuid, bigint, bigint, boolean, bigint, text, text) to anon, authenticated;

-- =============================================================================
-- END docs\SUPABASE_CHILD_DEVICE_SYNC_RPC.sql
-- =============================================================================


-- =============================================================================
-- BEGIN docs\SUPABASE_TASK_ASSIGNMENT_AND_STORAGE_PATCH.sql
-- =============================================================================

-- Family Time Manager - task assignment, task rewards, and task photo storage patch.
-- Run after the existing setup SQL on projects that were created before task assignment.

alter table public.tasks
add column if not exists assigned_device_id uuid references public.devices(id) on delete set null;

create index if not exists idx_tasks_family_assigned_device_status
on public.tasks(family_id, assigned_device_id, status, created_at desc);

-- Task photos use Supabase Storage bucket `task-submissions`.
insert into storage.buckets (id, name, public)
values ('task-submissions', 'task-submissions', true)
on conflict (id) do update set public = excluded.public;

drop policy if exists "family time task photo uploads" on storage.objects;
drop policy if exists "family time task photo updates" on storage.objects;
drop policy if exists "family time task photo deletes" on storage.objects;
drop policy if exists "family time task photo reads" on storage.objects;

create policy "family time task photo uploads"
on storage.objects
for insert
to anon, authenticated
with check (bucket_id = 'task-submissions');

create policy "family time task photo updates"
on storage.objects
for update
to anon, authenticated
using (bucket_id = 'task-submissions')
with check (bucket_id = 'task-submissions');

create policy "family time task photo deletes"
on storage.objects
for delete
to anon, authenticated
using (bucket_id = 'task-submissions');

create policy "family time task photo reads"
on storage.objects
for select
to anon, authenticated
using (bucket_id = 'task-submissions');

-- =============================================================================
-- END docs\SUPABASE_TASK_ASSIGNMENT_AND_STORAGE_PATCH.sql
-- =============================================================================


-- =============================================================================
-- BEGIN docs\SUPABASE_TASK_LIMITS_AND_CLEANUP.sql
-- =============================================================================

-- Family Time Manager task lifetime patch.
-- Run after the base schema/RLS files.
--
-- Rules:
-- 1. Tasks can be permanent or limited.
-- 2. Limited open tasks are auto-closed after expires_at.
-- 3. Submitted tasks can still be approved/rejected even after expires_at.
-- 4. Rejected/closed tasks are retained for at most 3 days, then the app deletes
--    their photos before deleting the task.

alter table public.tasks
  add column if not exists task_type text not null default 'permanent';

alter table public.tasks
  add column if not exists expires_at timestamptz;

alter table public.tasks
  add column if not exists closed_at timestamptz;

alter table public.tasks
  add column if not exists reviewed_at timestamptz;

alter table public.tasks
  drop constraint if exists tasks_task_type_check;

alter table public.tasks
  add constraint tasks_task_type_check
  check (task_type in ('permanent', 'limited'));

create index if not exists idx_tasks_expiry_status
  on public.tasks(status, task_type, expires_at);

create index if not exists idx_tasks_cleanup
  on public.tasks(status, closed_at, reviewed_at, created_at);

create or replace function public.close_expired_limited_tasks(
  p_family_id uuid,
  p_device_id uuid
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  target_family_id uuid;
begin
  select coalesce(p_family_id, d.family_id)
  into target_family_id
  from public.devices d
  where d.id = p_device_id
  limit 1;

  target_family_id := coalesce(target_family_id, p_family_id);

  if target_family_id is null then
    return;
  end if;

  update public.tasks
  set status = 'closed',
      closed_at = coalesce(closed_at, now())
  where task_type = 'limited'
    and status = 'open'
    and family_id = target_family_id
    and expires_at is not null
    and expires_at <= now();
end;
$$;

revoke all on function public.close_expired_limited_tasks(uuid, uuid) from public;
grant execute on function public.close_expired_limited_tasks(uuid, uuid) to anon, authenticated;

create or replace function public.submit_task_for_device(
  p_task_id uuid,
  p_device_id uuid,
  p_comment text,
  p_photo_url text
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  target_task public.tasks%rowtype;
  target_device public.devices%rowtype;
begin
  select *
  into target_task
  from public.tasks
  where id = p_task_id
  for update;

  if target_task.id is null then
    raise exception 'task_not_found';
  end if;

  select *
  into target_device
  from public.devices
  where id = p_device_id
    and family_id = target_task.family_id
    and coalesce(removed_at, 'infinity'::timestamptz) = 'infinity'::timestamptz
  limit 1;

  if target_device.id is null then
    raise exception 'device_not_found';
  end if;

  if target_task.assigned_device_id is not null and target_task.assigned_device_id <> target_device.id then
    raise exception 'task_not_for_device';
  end if;

  if target_task.status <> 'open' then
    raise exception 'task_not_open';
  end if;

  if target_task.task_type = 'limited'
     and target_task.expires_at is not null
     and target_task.expires_at <= now() then
    update public.tasks
    set status = 'closed',
        closed_at = coalesce(closed_at, now())
    where id = target_task.id;
    raise exception 'task_expired';
  end if;

  insert into public.task_submissions (
    task_id,
    photo_url,
    comment,
    status
  )
  values (
    target_task.id,
    nullif(p_photo_url, ''),
    trim(coalesce(p_comment, '')),
    'submitted'
  );

  update public.tasks
  set status = 'submitted'
  where id = target_task.id;
end;
$$;

revoke all on function public.submit_task_for_device(uuid, uuid, text, text) from public;
grant execute on function public.submit_task_for_device(uuid, uuid, text, text) to anon, authenticated;

create or replace function public.list_task_cleanup_candidates(
  p_family_id uuid,
  p_device_id uuid
)
returns table (
  task_id uuid,
  photo_url text
)
language plpgsql
security definer
set search_path = public
as $$
declare
  target_family_id uuid;
begin
  select coalesce(p_family_id, d.family_id)
  into target_family_id
  from public.devices d
  where d.id = p_device_id
  limit 1;

  target_family_id := coalesce(target_family_id, p_family_id);

  if target_family_id is null then
    return;
  end if;

  perform public.close_expired_limited_tasks(target_family_id, p_device_id);

  return query
  select
    t.id as task_id,
    coalesce(ts.photo_url, '') as photo_url
  from public.tasks t
  left join public.task_submissions ts on ts.task_id = t.id
  where t.status in ('closed', 'rejected')
    and t.family_id = target_family_id
    and coalesce(t.closed_at, t.reviewed_at, t.created_at) <= now() - interval '3 days';
end;
$$;

revoke all on function public.list_task_cleanup_candidates(uuid, uuid) from public;
grant execute on function public.list_task_cleanup_candidates(uuid, uuid) to anon, authenticated;

create or replace function public.delete_task_after_cleanup(
  p_task_id uuid,
  p_family_id uuid,
  p_device_id uuid
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  target_family_id uuid;
begin
  select coalesce(p_family_id, d.family_id)
  into target_family_id
  from public.devices d
  where d.id = p_device_id
  limit 1;

  target_family_id := coalesce(target_family_id, p_family_id);

  if target_family_id is null then
    return;
  end if;

  perform public.close_expired_limited_tasks(target_family_id, p_device_id);

  delete from public.tasks
  where id = p_task_id
    and family_id = target_family_id
    and status in ('closed', 'rejected')
    and coalesce(closed_at, reviewed_at, created_at) <= now() - interval '3 days';
end;
$$;

revoke all on function public.delete_task_after_cleanup(uuid, uuid, uuid) from public;
grant execute on function public.delete_task_after_cleanup(uuid, uuid, uuid) to anon, authenticated;

-- =============================================================================
-- END docs\SUPABASE_TASK_LIMITS_AND_CLEANUP.sql
-- =============================================================================


-- =============================================================================
-- BEGIN docs\list_tasks_for_device.sql
-- =============================================================================

-- Family Time Manager ??child task visibility fix
--
-- Why: a child device is anonymous (no signed-in parent account), so it cannot
-- read the `tasks` table directly ??row-level security (RLS) blocks it and the
-- child sees an empty task list. The store already solves this with the
-- SECURITY DEFINER function `list_store_products_for_device`. This adds the
-- equivalent function for tasks, which the app now calls from the child side.
--
-- How to run: Supabase dashboard -> SQL Editor -> paste -> Run.
--
-- NOTE on the parameter type: this uses `uuid` to match the other device RPCs.
-- If your `devices.id` / `tasks.assigned_device_id` columns are `text`, change
-- `p_device_id uuid` to `p_device_id text` (mirror your existing
-- `list_store_products_for_device` signature).

create or replace function public.list_tasks_for_device(p_device_id uuid)
returns setof public.tasks
language sql
security definer
set search_path = public
as $$
  select t.*
  from public.tasks t
  where t.assigned_device_id = p_device_id
  order by t.created_at desc
  limit 100;
$$;

grant execute on function public.list_tasks_for_device(uuid) to anon, authenticated;

-- =============================================================================
-- END docs\list_tasks_for_device.sql
-- =============================================================================


-- =============================================================================
-- BEGIN docs\get_parent_pin_for_device.sql
-- =============================================================================

-- Family Time Manager ??child must follow the family's synced parent PIN
--
-- Why: a child device is anonymous (no signed-in parent account), so it cannot read the
-- `families` table directly ??RLS blocks it. That means the child never picks up a changed
-- family PIN and keeps verifying against its old local PIN. This SECURITY DEFINER function lets
-- the child fetch the family's current parent PIN keyed by its own device id (same pattern as
-- list_tasks_for_device / list_store_products_for_device).
--
-- How to run: Supabase dashboard -> SQL Editor -> paste -> Run.
--
-- NOTE: parameter type is `uuid` to match the other device RPCs. If your devices.id column is
-- `text`, change `p_device_id uuid` to `p_device_id text`.

create or replace function public.get_parent_pin_for_device(p_device_id uuid)
returns table (parent_pin_hash text, parent_pin_enabled boolean)
language sql
security definer
set search_path = public
as $$
  select f.parent_pin_hash, f.parent_pin_enabled
  from public.families f
  join public.devices d on d.family_id = f.id
  where d.id = p_device_id
  limit 1;
$$;

grant execute on function public.get_parent_pin_for_device(uuid) to anon, authenticated;

-- =============================================================================
-- END docs\get_parent_pin_for_device.sql
-- =============================================================================


-- =============================================================================
-- BEGIN docs\SUPABASE_STORE.sql
-- =============================================================================

-- Family Time Manager reward store.
-- Run after SUPABASE_OWNER_FAMILY_DELETE.sql and SUPABASE_CHILD_DEVICE_CONNECT_RPC.sql.
--
-- Features:
-- 1. Parents create redeemable items for all children or one child device.
-- 2. Child devices list available items by device id without signing in.
-- 3. Child devices redeem with time wallet balance through a SECURITY DEFINER RPC.
-- 4. The app deletes the uploaded icon file after a successful purchase.

create table if not exists public.store_products (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references public.families(id) on delete cascade,
  name text not null,
  description text,
  price_seconds bigint not null check (price_seconds > 0),
  icon_url text,
  target_device_id uuid references public.devices(id) on delete set null,
  status text not null default 'active' check (status in ('active', 'purchased', 'deleted')),
  created_by uuid references public.profiles(id) on delete set null,
  purchased_by_device_id uuid references public.devices(id) on delete set null,
  purchased_at timestamptz,
  created_at timestamptz not null default now()
);

create table if not exists public.store_purchases (
  id uuid primary key default gen_random_uuid(),
  product_id uuid references public.store_products(id) on delete set null,
  family_id uuid not null references public.families(id) on delete cascade,
  device_id uuid not null references public.devices(id) on delete cascade,
  price_seconds bigint not null check (price_seconds > 0),
  icon_url text,
  purchased_at timestamptz not null default now()
);

create index if not exists idx_store_products_family_status
  on public.store_products(family_id, status, created_at desc);

create index if not exists idx_store_products_target_device
  on public.store_products(target_device_id, status, created_at desc);

create index if not exists idx_store_purchases_device_time
  on public.store_purchases(device_id, purchased_at desc);

alter table public.store_products enable row level security;
alter table public.store_purchases enable row level security;

drop policy if exists "parents manage store products" on public.store_products;
drop policy if exists "family members read store purchases" on public.store_purchases;

create policy "parents manage store products"
on public.store_products
for all
to authenticated
using (public.is_family_parent(family_id))
with check (public.is_family_parent(family_id));

create policy "family members read store purchases"
on public.store_purchases
for select
to authenticated
using (public.is_family_member(family_id));

create or replace function public.list_store_products_for_device(p_device_id uuid)
returns table (
  id uuid,
  name text,
  description text,
  price_seconds bigint,
  icon_url text,
  target_device_id uuid,
  status text,
  created_at timestamptz
)
language plpgsql
security definer
set search_path = public
as $$
declare
  target_device public.devices%rowtype;
begin
  select *
  into target_device
  from public.devices
  where devices.id = p_device_id
    and coalesce(devices.removed_at, 'infinity'::timestamptz) = 'infinity'::timestamptz
  limit 1;

  if target_device.id is null then
    return;
  end if;

  return query
  select
    sp.id,
    sp.name,
    sp.description,
    sp.price_seconds,
    sp.icon_url,
    sp.target_device_id,
    sp.status,
    sp.created_at
  from public.store_products sp
  join public.families f on f.id = sp.family_id
  where sp.family_id = target_device.family_id
    and f.deleted_at is null
    and sp.status = 'active'
    and (sp.target_device_id is null or sp.target_device_id = target_device.id)
  order by sp.created_at desc;
end;
$$;

revoke all on function public.list_store_products_for_device(uuid) from public;
grant execute on function public.list_store_products_for_device(uuid) to anon, authenticated;

create or replace function public.purchase_store_product(
  p_product_id uuid,
  p_device_id uuid
)
returns table (
  icon_url text,
  remaining_seconds bigint
)
language plpgsql
security definer
set search_path = public
as $$
declare
  target_product public.store_products%rowtype;
  target_device public.devices%rowtype;
  next_remaining bigint;
begin
  select *
  into target_product
  from public.store_products
  where id = p_product_id
    and status = 'active'
  for update;

  if target_product.id is null then
    raise exception 'product_not_available';
  end if;

  select *
  into target_device
  from public.devices
  where id = p_device_id
    and family_id = target_product.family_id
    and coalesce(removed_at, 'infinity'::timestamptz) = 'infinity'::timestamptz
  for update;

  if target_device.id is null then
    raise exception 'device_not_found';
  end if;

  if target_product.target_device_id is not null and target_product.target_device_id <> target_device.id then
    raise exception 'product_not_for_device';
  end if;

  if exists (
    select 1
    from public.families f
    where f.id = target_product.family_id
      and f.deleted_at is not null
  ) then
    raise exception 'family_deleted';
  end if;

  if target_device.remaining_seconds < target_product.price_seconds then
    raise exception 'not_enough_time';
  end if;

  next_remaining := target_device.remaining_seconds - target_product.price_seconds;

  update public.devices
  set remaining_seconds = next_remaining,
      locked = next_remaining <= 0,
      last_seen = now()
  where id = target_device.id;

  update public.store_products
  set status = 'purchased',
      purchased_by_device_id = target_device.id,
      purchased_at = now()
  where id = target_product.id;

  insert into public.store_purchases (
    product_id,
    family_id,
    device_id,
    price_seconds,
    icon_url
  )
  values (
    target_product.id,
    target_product.family_id,
    target_device.id,
    target_product.price_seconds,
    target_product.icon_url
  );

  icon_url := coalesce(target_product.icon_url, '');
  remaining_seconds := next_remaining;
  return next;
end;
$$;

revoke all on function public.purchase_store_product(uuid, uuid) from public;
grant execute on function public.purchase_store_product(uuid, uuid) to anon, authenticated;

-- =============================================================================
-- END docs\SUPABASE_STORE.sql
-- =============================================================================


-- =============================================================================
-- BEGIN docs\redemptions.sql
-- =============================================================================

-- Family Time Manager ??store redemption records + parent fulfilment flow
--
-- Adds a `redemptions` table that records each store purchase, so the parent can see who redeemed
-- what and mark it as fulfilled (e.g. after actually giving the reward). Child devices are
-- anonymous, so they write through a SECURITY DEFINER RPC; the parent reads/updates through
-- definer RPCs keyed by the family id (same trust model the app already uses elsewhere).
--
-- How to run: Supabase dashboard -> SQL Editor -> paste -> Run.
-- NOTE: uuid types assume devices.id / families.id are uuid. Adjust to text if your schema differs.

create table if not exists public.redemptions (
    id uuid primary key default gen_random_uuid(),
    family_id uuid not null references public.families(id) on delete cascade,
    device_id uuid references public.devices(id) on delete set null,
    product_id uuid,
    product_name text not null default '',
    price_seconds bigint not null default 0,
    status text not null default 'pending',          -- 'pending' | 'fulfilled'
    created_at timestamptz not null default now(),
    fulfilled_at timestamptz
);

alter table public.redemptions enable row level security;

-- Child (anonymous) records a redemption right after a successful purchase.
create or replace function public.record_redemption_for_device(
    p_device_id uuid,
    p_product_id uuid,
    p_product_name text,
    p_price_seconds bigint
) returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    v_family uuid;
    v_id uuid;
begin
    select family_id into v_family from public.devices where id = p_device_id;
    if v_family is null then
        raise exception 'device not found';
    end if;
    insert into public.redemptions (family_id, device_id, product_id, product_name, price_seconds)
    values (v_family, p_device_id, p_product_id, coalesce(p_product_name, ''), coalesce(p_price_seconds, 0))
    returning id into v_id;
    return v_id;
end;
$$;

grant execute on function public.record_redemption_for_device(uuid, uuid, text, bigint) to anon, authenticated;

-- Parent lists redemptions for the family, including the child device name.
create or replace function public.list_redemptions_for_family(p_family_id uuid)
returns table (
    id uuid,
    device_id uuid,
    device_name text,
    product_name text,
    price_seconds bigint,
    status text,
    created_at timestamptz,
    fulfilled_at timestamptz
)
language sql
security definer
set search_path = public
as $$
    select r.id, r.device_id, coalesce(d.device_name, ''), r.product_name,
           r.price_seconds, r.status, r.created_at, r.fulfilled_at
    from public.redemptions r
    left join public.devices d on d.id = r.device_id
    where r.family_id = p_family_id
    order by r.created_at desc
    limit 100;
$$;

grant execute on function public.list_redemptions_for_family(uuid) to authenticated;

-- Parent marks a redemption fulfilled (or back to pending).
create or replace function public.set_redemption_status(p_redemption_id uuid, p_status text)
returns void
language sql
security definer
set search_path = public
as $$
    update public.redemptions
       set status = p_status,
           fulfilled_at = case when p_status = 'fulfilled' then now() else null end
     where id = p_redemption_id;
$$;

grant execute on function public.set_redemption_status(uuid, text) to authenticated;

-- Parent deletes a redemption record from its own family.
create or replace function public.delete_redemption_for_family(
    p_redemption_id uuid,
    p_family_id uuid
) returns void
language sql
security definer
set search_path = public
as $$
    delete from public.redemptions
     where id = p_redemption_id
       and family_id = p_family_id;
$$;

grant execute on function public.delete_redemption_for_family(uuid, uuid) to authenticated;

-- =============================================================================
-- END docs\redemptions.sql
-- =============================================================================


-- =============================================================================
-- BEGIN docs\SUPABASE_REMOTE_DAILY_WALLET.sql
-- =============================================================================

-- Family Time Manager remote daily wallet rules.
-- Run after the base schema/RLS files.
--
-- This makes the daily wallet rule a per-child-device setting. Parents send
-- SET_DAILY_WALLET_RULE commands from Remote Control; the child device applies the
-- rule locally and pushes the latest rule back to devices.

alter table public.devices
  add column if not exists daily_wallet_mode text not null default 'NONE';

alter table public.devices
  add column if not exists daily_wallet_amount_seconds bigint not null default 0
  check (daily_wallet_amount_seconds >= 0);

alter table public.devices
  add column if not exists daily_wallet_last_applied_date text not null default '';

alter table public.devices
  drop constraint if exists devices_daily_wallet_mode_check;

alter table public.devices
  add constraint devices_daily_wallet_mode_check
  check (daily_wallet_mode in ('NONE', 'ADD', 'ZERO', 'RESET'));

alter table public.commands
  drop constraint if exists commands_command_check;

alter table public.commands
  add constraint commands_command_check
  check (
    command in (
      'ADD_TIME',
      'DEDUCT_TIME',
      'SET_TIME',
      'LOCK',
      'UNLOCK',
      'UPDATE_PIN',
      'SET_DAILY_WALLET_RULE',
      'TASK_ASSIGNED',
      'TASK_SUBMITTED',
      'TASK_APPROVED',
      'TASK_REJECTED',
      'TASK_DELETED'
    )
  );

-- =============================================================================
-- END docs\SUPABASE_REMOTE_DAILY_WALLET.sql
-- =============================================================================

