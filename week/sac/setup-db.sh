#!/usr/bin/env bash
# Smart Academic Companion - DB setup script
# Creates the database on PostgreSQL.
# Usage: DB_PASSWORD='your-postgres-password' bash setup-db.sh
set -e

PSQL="/c/Program Files/PostgreSQL/18/bin/psql"
DB_PASSWORD="${DB_PASSWORD:?Set DB_PASSWORD to your postgres password}"

export PGPASSWORD="$DB_PASSWORD"

"$PSQL" -U postgres -h localhost -c "CREATE DATABASE smart_academic_companion;" 2>/dev/null \
  && echo "Database created." || echo "Database already exists (ok)."

echo "Done. Now set your password in src/main/resources/application.yml and run ./run.sh"
