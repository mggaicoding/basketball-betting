create table game (
    id varchar(40) primary key,
    home_team varchar(120) not null,
    away_team varchar(120) not null,
    start_time timestamp with time zone not null,
    home_odds numeric(10, 2) not null,
    away_odds numeric(10, 2) not null
);
