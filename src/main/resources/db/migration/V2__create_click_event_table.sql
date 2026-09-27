CREATE TABLE click_event (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    link_id    BIGINT      NOT NULL REFERENCES link (id) ON DELETE CASCADE,
    clicked_at TIMESTAMPTZ NOT NULL,
    referrer   TEXT,
    user_agent TEXT,

    CONSTRAINT click_referrer_length   CHECK (referrer IS NULL OR length(referrer) <= 512),
    CONSTRAINT click_user_agent_length CHECK (user_agent IS NULL OR length(user_agent) <= 512)
);

-- No IP address column, deliberately. It is personal data under GDPR, it would
-- need a retention policy and a lawful basis, and none of the statistics this
-- service reports require it.

-- Every statistics query is "this link, over this period", so the index leads
-- with link_id and orders by time within it.
CREATE INDEX click_event_link_time_idx ON click_event (link_id, clicked_at DESC);
