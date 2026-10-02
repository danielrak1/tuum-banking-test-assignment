CREATE TABLE account (
    id          uuid         PRIMARY KEY,
    customer_id varchar(64)  NOT NULL,
    country     char(2)      NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE balance (
    account_id       uuid          NOT NULL REFERENCES account(id),
    currency         varchar(3)    NOT NULL CHECK (currency IN ('EUR','SEK','GBP','USD')),
    available_amount numeric(19,2) NOT NULL DEFAULT 0 CHECK (available_amount >= 0),
    PRIMARY KEY (account_id, currency)
);

CREATE TABLE account_transaction (
    id            uuid          PRIMARY KEY,
    account_id    uuid          NOT NULL REFERENCES account(id),
    currency      varchar(3)    NOT NULL CHECK (currency IN ('EUR','SEK','GBP','USD')),
    direction     varchar(3)    NOT NULL CHECK (direction IN ('IN','OUT')),
    amount        numeric(19,2) NOT NULL CHECK (amount > 0),
    description   varchar(255)  NOT NULL,
    balance_after numeric(19,2) NOT NULL,
    seq           bigserial     NOT NULL,   -- list order
    created_at    timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX account_transaction_account_idx ON account_transaction (account_id, seq);

CREATE TABLE outbox_event (
    id          bigserial    PRIMARY KEY,   -- publish order (per balance = commit order)
    event_id    uuid         NOT NULL UNIQUE,
    routing_key varchar(64)  NOT NULL,
    payload     jsonb        NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);
