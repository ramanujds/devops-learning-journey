#!/bin/bash
set -e

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
    CREATE DATABASE IF NOT EXISTS part_inventory_db;
    CREATE DATABASE IF NOT EXISTS part_order_db;

    CREATE USER IF NOT EXISTS 'inventory_app'@'%' IDENTIFIED BY '${INVENTORY_DB_PASSWORD}';
    GRANT ALL PRIVILEGES ON part_inventory_db.* TO 'inventory_app'@'%';

    CREATE USER IF NOT EXISTS 'order_app'@'%' IDENTIFIED BY '${ORDER_DB_PASSWORD}';
    GRANT ALL PRIVILEGES ON part_order_db.* TO 'order_app'@'%';

    FLUSH PRIVILEGES;
EOSQL
