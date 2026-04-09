-- prev: 0001-init.sql

-- convert uuid columns to text

create table client_new(
    id text primary key,
    data text not null
);
insert into client_new(id, data) select cast(id as text), data from client;
drop table client;
alter table client_new rename to client;

create table consent_new(
    user_id text not null,
    client_id text not null,
    data text not null
);
insert into consent_new(user_id, client_id, data) select cast(user_id as text), cast(client_id as text), data from consent;
drop table consent;
alter table consent_new rename to consent;
create unique index consent_user_client_idx on consent(user_id, client_id);
