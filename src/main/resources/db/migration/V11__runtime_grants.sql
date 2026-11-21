do $$
begin
    if current_user = 'care_migrator' then
        revoke insert, update, delete on flyway_schema_history from care_app;
        revoke update, delete on audit_log from care_app;
    end if;
end
$$;
