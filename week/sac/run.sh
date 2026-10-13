#!/usr/bin/env bash
# Build + run Smart Academic Companion
# Prereq: database created (see setup-db.sh) and password set in application.yml
cd "$(dirname "$0")"
mvn -q spring-boot:run
