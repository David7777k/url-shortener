-- Token bucket, evaluated inside Redis.
--
-- Read-modify-write across several commands would be a race: two requests can
-- both read the same token count before either writes, and both get let
-- through. A script runs to completion without another client interleaving, so
-- the whole decision is one atomic step.
--
-- KEYS[1]  bucket key
-- ARGV[1]  capacity, the largest burst allowed
-- ARGV[2]  refill rate, tokens per second
-- ARGV[3]  now, in milliseconds
-- ARGV[4]  tokens this request costs
-- ARGV[5]  key time-to-live in seconds
--
-- returns {allowed, tokens remaining, milliseconds until the next token}

local key       = KEYS[1]
local capacity  = tonumber(ARGV[1])
local rate      = tonumber(ARGV[2])
local now       = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])
local ttl       = tonumber(ARGV[5])

local bucket  = redis.call('HMGET', key, 'tokens', 'updated')
local tokens  = tonumber(bucket[1])
local updated = tonumber(bucket[2])

-- An unseen client starts with a full bucket, so a first request is never
-- refused.
if tokens == nil or updated == nil then
    tokens = capacity
    updated = now
end

-- Refill for the time that has passed. Clamped at zero: a clock moving
-- backwards must not hand out tokens for negative time.
local elapsed = math.max(0, now - updated) / 1000.0
tokens = math.min(capacity, tokens + elapsed * rate)

local allowed = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
end

local retry_after_ms = 0
if allowed == 0 then
    retry_after_ms = math.ceil(((requested - tokens) / rate) * 1000)
end

redis.call('HSET', key, 'tokens', tokens, 'updated', now)
-- Expire idle buckets rather than keeping a key per client seen since boot.
redis.call('EXPIRE', key, ttl)

return { allowed, math.floor(tokens), retry_after_ms }
