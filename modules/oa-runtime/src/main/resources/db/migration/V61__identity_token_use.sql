-- WP-102 slice c: use counter of delegated tokens, keyed by issuer and token id (jti), so that a token accepted by a
-- resource more often than allowed is refused, across restarts and across runtime replicas.
-- Only the key and a count are stored. The token text is never stored.
CREATE TABLE identity.token_use (
    token_key  text        PRIMARY KEY CHECK (char_length(token_key) BETWEEN 1 AND 600),
    uses       integer     NOT NULL CHECK (uses >= 1),
    expires_at timestamptz NOT NULL
);

CREATE INDEX token_use_expires_idx ON identity.token_use (expires_at);
