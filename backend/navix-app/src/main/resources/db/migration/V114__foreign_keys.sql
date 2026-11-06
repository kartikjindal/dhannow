-- V114 (PLAN Phase 9): foreign keys the DhanNow tables (V97 on) were missing. navix tables are untouched.
-- Most V97+ references were declared inline already (loan, payment, loan_extension, loan_document, staff_user ...).
-- Not keyed, on purpose:
--   * customer_id: there is no customer table; borrower_mobile is the sign-in registry and its row goes at purge;
--   * actor columns (proposed_by, approved_by, decided_by, actor_id ...): they also record system actions;
--   * recording_id, reconciliation_id: ids in other systems.

-- An extension offer is always made from a collection case.
alter table loan_extension
    add constraint fk_loan_extension_collection_case foreign key (collection_case_id) references collection_case (id);

-- A complaint is assigned to a staff member (ComplaintService.assign checks it is an active one).
alter table complaint
    add constraint fk_complaint_assigned_to foreign key (assigned_to) references staff_user (id);

-- App sessions and push tokens belong to a registered borrower and go with the registration (CC-45 purge).
alter table refresh_token
    add constraint fk_refresh_token_borrower foreign key (customer_id) references borrower_mobile (customer_id)
        on delete cascade;
alter table device_token
    add constraint fk_device_token_borrower foreign key (customer_id) references borrower_mobile (customer_id)
        on delete cascade;
