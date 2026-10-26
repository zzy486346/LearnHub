#!/bin/sh
set -eu

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 \
  -f /bootstrap/001_course_embeddings.sql
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 \
  -f /bootstrap/002_siliconflow_bge_large_embeddings.sql
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 \
  --set runtime_user="$LEARNHUB_VECTOR_USERNAME" --set runtime_password="$LEARNHUB_VECTOR_PASSWORD" <<'SQL'
CREATE ROLE :"runtime_user" LOGIN PASSWORD :'runtime_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE learnhub_vector FROM PUBLIC;
GRANT CONNECT ON DATABASE learnhub_vector TO :"runtime_user";
GRANT USAGE ON SCHEMA learnhub_rag, public TO :"runtime_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON learnhub_rag.course_embeddings_bge_zh_v1 TO :"runtime_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON learnhub_rag.course_embeddings_sf_bge_large_zh_v1 TO :"runtime_user";
SQL
