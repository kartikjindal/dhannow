-- V19: seed the first ADMIN row for the staff console, with no password. BootstrapAdminRunner sets
-- its password on boot from NAVIX_BOOTSTRAP_ADMIN_PASSWORD. Guarded with WHERE NOT EXISTS so a re-run
-- is a no-op.

insert into staff_user (email, name, role, status, password_hash, created_at, created_by)
select 'admin@dhannow.com', 'NAVIX Admin', 'ADMIN', 'ACTIVE',
       null,
       now(), 'seed'
 where not exists (select 1 from staff_user where email = 'admin@dhannow.com');
