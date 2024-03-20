#!/bin/bash

LATEST_BACKUP=$(date '+%Y%m%d_%H%M%S')
LATEST_BACKUP=backup_$(echo $LATEST_BACKUP | sed -e "s/\r//")
LATEST_BACKUP="$LATEST_BACKUP.sql.gz"
echo $LATEST_BACKUP

echo "[INFO] Started exporting latest backup: '$LATEST_BACKUP'"
docker compose exec -T postgres sh -c 'pg_dump -cU $POSTGRES_USER $POSTGRES_DB' | gzip > ./backups/${LATEST_BACKUP}
echo "[INFO] Finished exporting latest backup: '$LATEST_BACKUP'"

umask 022
chmod 644 ./backups/*.gz

