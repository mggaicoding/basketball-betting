create table bet (
    id varchar(40) primary key,
    game_id varchar(40) not null references game(id),
    selection varchar(10) not null,
    stake numeric(12, 2) not null,
    odds numeric(10, 2) not null,
    status varchar(20) not null,
    created_at timestamp with time zone not null
);
