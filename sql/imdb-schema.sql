-- 在两库各自的 imdb 数据库中执行，再按表头顺序导入 TSV。
create table public.title_basics (
    tconst text primary key,
    titletype text,
    primarytitle text,
    originaltitle text,
    isadult text,
    startyear text,
    endyear text,
    runtimeminutes text,
    genres text
);
