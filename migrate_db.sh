#!/bin/bash

if [[ -z ${COMPOSER} ]]; then
    echo "[ERROR]: > you need to specify which composer you need to run! eg: COMPOSER=docker compose";
    return 1
fi

if [[ -z ${DB_TO_RESTORE} ]]; then
    echo "[ERROR]: > you need to specify which DB to restore! eg: DB_TO_RESTORE=my_db.sql";
    return 1
fi


echo "[INFO] Started stopping containers for netbox migration"
2>>./migrate_db.log &>>./migrate_db.log $COMPOSER stop netbox netbox-worker netbox-housekeeping
echo "[INFO] Finished stopping containers for netbox migration"

echo "[INFO] Started migrating netbox db"
gunzip -c ./backups/$DB_TO_RESTORE | $COMPOSER exec -T postgres sh -c 'psql -U $POSTGRES_USER $POSTGRES_DB'
echo "[INFO] Finished migrating netbox db"

echo "[INFO] Starting Netbox service"
$COMPOSER start netbox netbox-worker netbox-housekeeping
echo "[INFO] Finished Starting Netbox service"

# OLD method using .sql instead of gzip
# echo "[INFO] Started resetting netbox db"
# $COMPOSER exec -ti postgres bash -c '''
#     psql -d postgres -U $POSTGRES_USER -c "drop database netbox;"
# '''

# if [[ $? -ne 0 ]]; then
#     echo "[INFO] Failed resetting netbox db"
#     return 1
# fi
# echo "[INFO] Finished resetting netbox db"

# echo "[INFO] Started creating new netbox DB"
# 2>>./migrate_db.log &>>./migrate_db.log $COMPOSER exec -ti postgres bash -c '''
#   psql -d postgres -U $POSTGRES_USER -c "create database netbox;"
# '''
# echo "[INFO] Finished creating new netbox DB"

# echo "[INFO] Started granting postgres user all privileges on netbox DB"
# 2>>./migrate_db.log &>>./migrate_db.log $COMPOSER exec -ti postgres bash -c '''
#     psql -d postgres -U $POSTGRES_USER -c "grant all privileges on database netbox to netbox;"
# '''
# echo "[INFO] Finished granting postgres user all privileges on netbox DB"

# echo "[INFO] Started restoring netbox backups on new DB"
# 2>>./migrate_db.log &>>./migrate_db.log $COMPOSER exec -ti postgres bash -c """
#     pg_restore -v -Fc -c -U \$POSTGRES_USER -d \$POSTGRES_DB <  /opt/backups/${DB_TO_RESTORE}
# """
# echo "[INFO] Finished restoring netbox backups on new DB"


# echo "[INFO] Starting Netbox service"
# $COMPOSER start netbox netbox-worker netbox-housekeeping
# echo "[INFO] Finished Starting Netbox service"