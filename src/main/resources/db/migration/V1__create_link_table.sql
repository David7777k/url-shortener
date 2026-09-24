CREATE TABLE link (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       TEXT        NOT NULL,
    target_url TEXT        NOT NULL,
    created_by TEXT,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT link_code_key UNIQUE (code),

    -- Base62 alphabet. Deliberately does not assume how codes are produced:
    -- that decision belongs to issue #2.
    CONSTRAINT link_code_shape CHECK (code ~ '^[0-9A-Za-z]{1,16}$'),

    CONSTRAINT link_target_absolute CHECK (target_url ~* '^https?://'),
    CONSTRAINT link_target_length   CHECK (length(target_url) <= 2048),
    CONSTRAINT link_expiry_future   CHECK (expires_at IS NULL OR expires_at > created_at)
);

-- The redirect looks a link up by code and nothing else, and link_code_key
-- already indexes that. No second index here on purpose.

-- Partial: only links that can expire are ever scanned for expiry.
CREATE INDEX link_expiry_idx ON link (expires_at) WHERE expires_at IS NOT NULL;
