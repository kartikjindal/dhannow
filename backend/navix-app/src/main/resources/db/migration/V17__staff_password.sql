-- P6: real staff auth. BCrypt password hash on staff_user (nullable: set at invite-accept, or for the
-- first admin by BootstrapAdminRunner). DhanNow seeds no password hash.
alter table staff_user add column password_hash varchar(255);
