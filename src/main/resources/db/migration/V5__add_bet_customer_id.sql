-- Sharing a bet needs an owner, and until now the customer only reached the risk check.
-- Three steps because the column is NOT NULL on a table that already has rows.
alter table bet add column customer_id varchar(64);
update bet set customer_id = 'unknown' where customer_id is null;
alter table bet alter column customer_id set not null;

create index if not exists idx_bet_customer_id on bet (customer_id);
