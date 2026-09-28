GRANT USAGE ON SCHEMA public TO identity_app;
GRANT EXECUTE ON FUNCTION authenticate(text), memberships_for(uuid), create_session(text,uuid,uuid), session_access(text), revoke_session(text) TO identity_app;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM starcon_app;
