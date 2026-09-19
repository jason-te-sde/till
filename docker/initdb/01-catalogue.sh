#!/bin/sh
# The storefront gets a database of its own.
#
# Not a schema in till's database: two services sharing one database can join across each other's
# tables, and the first such join is where a storefront starts deciding whether a sale is allowed.
# Separate databases make that a compile-time impossibility rather than a code review note.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
    create database catalogue;
SQL
