-- The console follows CONSOLE_ENABLED on every start (#326); a stored value would no longer be
-- read, and service list would show it as if it counted.
DELETE FROM operation_config WHERE operation_group = 'service:console';
