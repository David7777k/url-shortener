-- Fills the link table so a measurement means something.
--
-- On a few dozen rows PostgreSQL keeps everything in memory and both the
-- cached and uncached paths look identical, which would make the whole
-- exercise a way of proving nothing.

INSERT INTO link (code, target_url)
SELECT
    -- A deterministic seven-character Base62 code per row. Deterministic so
    -- the same seed produces the same codes and a run is repeatable.
    substr(md5(g::text), 1, 7),
    'https://example.com/destination/' || g
FROM generate_series(1, 200000) g
ON CONFLICT (code) DO NOTHING;

ANALYZE link;
