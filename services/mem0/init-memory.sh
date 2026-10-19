#!/bin/sh
set -eu
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 -f /bootstrap/mem0_collection.sql
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 \
  --set runtime_password="$LEARNHUB_MEMORY_PG_PASSWORD" --set memory_database="$POSTGRES_DB" <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE ROLE memory_runtime LOGIN PASSWORD :'runtime_password';
GRANT CONNECT ON DATABASE :"memory_database" TO memory_runtime;
GRANT USAGE ON SCHEMA public TO memory_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON public.learning_preferences_bge512_v1 TO memory_runtime;
SQL
