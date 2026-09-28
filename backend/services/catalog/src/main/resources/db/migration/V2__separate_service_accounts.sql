GRANT USAGE ON SCHEMA public TO catalog_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO catalog_app;
REVOKE UPDATE,DELETE ON audit_event FROM catalog_app;
REVOKE ALL ON flyway_schema_history FROM catalog_app;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM starcon_app;
