#!/bin/sh
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=migrator_password="$(cat /run/secrets/migrator_password)" \
  --set=application_password="$(cat /run/secrets/application_password)" <<'SQL'
create extension pgcrypto;
create role care_migrator login password :'migrator_password';
create role care_app login password :'application_password';
revoke create on schema public from public;
alter schema public owner to care_migrator;
grant usage on schema public to care_app;
alter default privileges for role care_migrator in schema public
  grant select, insert, update, delete on tables to care_app;
alter default privileges for role care_migrator in schema public
  grant usage, select on sequences to care_app;
SQL
