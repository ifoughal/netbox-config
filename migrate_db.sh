#!/bin/bash

if [[ -z ${COMPOSER} ]]; then
    export COMPOSER="docker compose"
    echo "[WARNING] COMPOSER variable not set. Defaulting to 'docker compose'."
fi

# parses args and ensure that backup filename is provided
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <backup-filename>"
    exit 1
fi

# ensure that backup file exists
BACKUP_FILE="$1"
if [ ! -f "$BACKUP_FILE" ]; then
    echo "[ERROR] Backup file '$BACKUP_FILE' does not exist."
    exit 1
fi

VOLUME=$($$COMPOSER ps -q postgres | xargs docker inspect --format '{{ json .Mounts }}' \
    | jq -r '.[] | select(.Type=="volume") | .Name')

if [ -z "$VOLUME" ]; then
    echo "[ERROR] Could not determine the database volume name."
    exit 1
fi

echo "[INFO] Stopping database container..."
output=$($$COMPOSER down 2>&1)
if [ $? -ne 0 ]; then
    echo "[ERROR] Failed to stop containers: $output"
    exit 1
fi

echo "[INFO] Removing database volume '$VOLUME'..."
output=$(docker volume rm "$VOLUME" 2>&1)
if [ $? -ne 0 ]; then
    echo "[ERROR] Failed to remove volume '$VOLUME': $output"
    exit 1
fi

echo "[INFO] Creating new postgres container..."
output=$($$COMPOSER up -d postgres 2>&1)
if [ $? -ne 0 ]; then
    echo "[ERROR] Failed to start postgres container: $output"
    exit 1
fi

echo "[INFO] Waiting for PostgreSQL to become ready..."
until $$COMPOSER exec -T postgres pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; do
    sleep 1
done
echo "[INFO] PostgreSQL is ready!"

# Restore the database
echo "[INFO] Restoring database from backup '$BACKUP_FILE'..."
output=$(gunzip -c $BACKUP_FILE | $$COMPOSER exec -T postgres sh -c 'psql -U $POSTGRES_USER $POSTGRES_DB' 2>&1)
if [ $? -ne 0 ]; then
    echo "[ERROR] Failed to restore database: $output"
    exit 1
fi


# Start all other containers
output=$($$COMPOSER up -d 2>&1)
if [ $? -ne 0 ]; then
    echo "[ERROR] Failed to start other containers: $output"
    exit 1
fi

echo "[INFO] Finished restoring database from backup '$BACKUP_FILE'."
