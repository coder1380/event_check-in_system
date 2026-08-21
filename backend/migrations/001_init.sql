CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE IF NOT EXISTS users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email CITEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  full_name TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('organizer', 'attendee')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  organizer_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  name TEXT NOT NULL,
  event_date TIMESTAMPTZ NOT NULL,
  capacity INTEGER NOT NULL CHECK (capacity > 0),
  registered_count INTEGER NOT NULL DEFAULT 0 CHECK (registered_count >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT capacity_not_exceeded CHECK (registered_count <= capacity)
);

CREATE TABLE IF NOT EXISTS registrations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  attendee_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status TEXT NOT NULL DEFAULT 'registered' CHECK (status IN ('registered', 'cancelled')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT unique_active_registration UNIQUE (event_id, attendee_id)
);
CREATE INDEX IF NOT EXISTS idx_registrations_event ON registrations(event_id);

CREATE TABLE IF NOT EXISTS check_in_tokens (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  registration_id UUID NOT NULL REFERENCES registrations(id) ON DELETE CASCADE,
  token TEXT NOT NULL UNIQUE,
  issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  used_at TIMESTAMPTZ,
  CONSTRAINT one_active_token_per_registration EXCLUDE USING gist (registration_id WITH =)
    WHERE (used_at IS NULL)
);
CREATE INDEX IF NOT EXISTS idx_tokens_registration ON check_in_tokens(registration_id);
CREATE INDEX IF NOT EXISTS idx_tokens_lookup ON check_in_tokens(token) WHERE used_at IS NULL;

CREATE TABLE IF NOT EXISTS checkins (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  registration_id UUID NOT NULL REFERENCES registrations(id) ON DELETE CASCADE,
  token_id UUID REFERENCES check_in_tokens(id),
  checked_in_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  station_id TEXT NOT NULL,
  source TEXT NOT NULL DEFAULT 'online' CHECK (source IN ('online', 'offline_sync')),
  CONSTRAINT unique_checkin_per_registration UNIQUE (registration_id)
);
CREATE INDEX IF NOT EXISTS idx_checkins_event ON checkins(checked_in_at);

CREATE TABLE IF NOT EXISTS offline_sync_log (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  registration_id UUID NOT NULL REFERENCES registrations(id),
  station_id TEXT NOT NULL,
  client_scanned_at TIMESTAMPTZ NOT NULL,
  synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  outcome TEXT NOT NULL CHECK (outcome IN ('accepted', 'rejected_duplicate')),
  resulting_checkin_id UUID REFERENCES checkins(id)
);