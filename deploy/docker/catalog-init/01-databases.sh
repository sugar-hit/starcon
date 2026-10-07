#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v runtime_password="$APP_DB_PASSWORD" <<'SQL'
SELECT 'CREATE ROLE starcon_app NOLOGIN' WHERE NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='starcon_app') \gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOBYPASSRLS', role_name, :'runtime_password') FROM (VALUES ('identity_app'),('catalog_app'),('channel_app'),('warehouse_app'),('trade_app')) roles(role_name) WHERE NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=role_name) \gexec
SELECT 'CREATE DATABASE starcon_identity' WHERE NOT EXISTS(SELECT 1 FROM pg_database WHERE datname='starcon_identity') \gexec
SELECT 'CREATE DATABASE starcon_catalog' WHERE NOT EXISTS(SELECT 1 FROM pg_database WHERE datname='starcon_catalog') \gexec
SELECT 'CREATE DATABASE starcon_channel' WHERE NOT EXISTS(SELECT 1 FROM pg_database WHERE datname='starcon_channel') \gexec
SELECT 'CREATE DATABASE starcon_warehouse' WHERE NOT EXISTS(SELECT 1 FROM pg_database WHERE datname='starcon_warehouse') \gexec
SELECT 'CREATE DATABASE starcon_trade' WHERE NOT EXISTS(SELECT 1 FROM pg_database WHERE datname='starcon_trade') \gexec
REVOKE CONNECT ON DATABASE starcon_identity FROM PUBLIC;
REVOKE CONNECT ON DATABASE starcon_catalog FROM PUBLIC;
REVOKE CONNECT ON DATABASE starcon_channel FROM PUBLIC;
REVOKE CONNECT ON DATABASE starcon_warehouse FROM PUBLIC;
REVOKE CONNECT ON DATABASE starcon_trade FROM PUBLIC;
GRANT CONNECT ON DATABASE starcon_identity TO identity_app;
GRANT CONNECT ON DATABASE starcon_catalog TO catalog_app;
GRANT CONNECT ON DATABASE starcon_channel TO channel_app;
GRANT CONNECT ON DATABASE starcon_warehouse TO warehouse_app;
GRANT CONNECT ON DATABASE starcon_trade TO trade_app;
SQL
