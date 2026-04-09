-- prev: base

create table user_info(
    id text primary key,
    name text not null,
    data text not null
);

create table jwks(
    kid text primary key,
    private_key bytea not null, -- PKCS#8 DER format, assumed to be secp256r1 keys for now
    public_key bytea not null -- X.509 DER format
);

create table client(
    id uuid primary key,
    data text not null
);

create table consent(
    user_id uuid not null,
    client_id uuid not null,
    data text not null
);
create unique index consent_user_client_idx on consent(user_id, client_id);

create table refresh_token(
    token_sha256 bytea not null primary key,
    data text not null
);

-- dummy test data
