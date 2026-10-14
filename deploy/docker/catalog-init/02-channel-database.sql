-- 为 channel 服务创建运行角色与独立数据库（本地开发用，与 catalog-init/01-databases.sh 保持一致）
-- 用法：docker exec -i <pg-container> psql -U starcon_migrator -d postgres -v pw="$APP_DB_PASSWORD" -f - < 本文件
-- Docker's entrypoint executes .sql files without psql -v arguments.
-- Preserve an explicit manual variable, otherwise read the container environment.
\if :{?pw}
\else
\getenv pw APP_DB_PASSWORD
\endif

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOBYPASSRLS', 'channel_app', :'pw')
  WHERE NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='channel_app') \gexec
SELECT 'CREATE DATABASE starcon_channel'
  WHERE NOT EXISTS(SELECT 1 FROM pg_database WHERE datname='starcon_channel') \gexec
REVOKE CONNECT ON DATABASE starcon_channel FROM PUBLIC;
GRANT CONNECT ON DATABASE starcon_channel TO channel_app;
