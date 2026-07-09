-- Time Wallet closed-test recruitment form.
-- Run this in Supabase SQL Editor before publishing docs/closed-test-recruitment.html.

create table if not exists public.closed_test_applications (
  id uuid primary key default gen_random_uuid(),
  created_at timestamptz not null default now(),
  parent_name text not null,
  email text not null,
  child_age text not null,
  device text not null,
  android_version text,
  contact text,
  message text,
  source text not null default 'facebook',
  user_agent text,
  status text not null default 'new'
    check (status in ('new', 'reviewed', 'invited', 'rejected')),
  invited_at timestamptz,
  notes text
);

create index if not exists closed_test_applications_created_at_idx
  on public.closed_test_applications(created_at desc);

create index if not exists closed_test_applications_email_idx
  on public.closed_test_applications(lower(email));

alter table public.closed_test_applications enable row level security;

drop policy if exists "anyone can register closed test application"
  on public.closed_test_applications;

create policy "anyone can register closed test application"
  on public.closed_test_applications
  for insert
  to anon
  with check (
    length(trim(parent_name)) > 0
    and email ~* '^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$'
    and length(trim(child_age)) > 0
    and length(trim(device)) > 0
    and coalesce(length(message), 0) <= 2000
  );

-- No public SELECT policy is created on purpose.
-- View/export applications from the Supabase dashboard or a service-role admin tool.
