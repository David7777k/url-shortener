# Measurements

Numbers, and what they do and do not show.

## Setup

| | |
|---|---|
| Rows in `link` | 199 915 |
| Table size | 27 MB |
| PostgreSQL | 17, in Docker on the same machine |
| Redis | 7, in Docker on the same machine |
| Load generator | k6, 20 s at a constant arrival rate |
| Codes | 10 000 real codes, sampled at random per request |

Everything runs on one laptop. That matters for reading the results: the
database is a millisecond away, so this measures a best case for the
uncached path and understates what a cache is worth when the database is
across a network.

## The index

```sql
explain (analyze, buffers) select id, target_url, expires_at
from link where code = 'c4ca423';
```

| | With the index | Index scan disabled |
|---|---|---|
| Execution time | **0.077 ms** | **9.760 ms** |
| Shared buffers read | 4 | 2 272 |
| Rows discarded by filter | — | 199 914 |

**127× slower and 568× more pages read.** The sequential scan examines every
row to find one. This is the clearest result here, and the least surprising.

## The cache

Two runs of the same build, with `trimly.cache.enabled` flipped. Comparing two
different builds would measure the builds as much as the cache.

The cache is warmed before the cached run, so this measures hits rather than a
mix of hits and misses.

### 300 requests per second

| | No cache | Cached | |
|---|---|---|---|
| avg | 2.05 ms | **1.45 ms** | −29% |
| p50 | 1.95 ms | **1.37 ms** | −30% |
| p95 | 2.56 ms | **1.80 ms** | −30% |

### 2 000 requests per second

| | No cache | Cached | |
|---|---|---|---|
| avg | 1.69 ms | **1.52 ms** | −10% |
| p50 | 1.27 ms | 1.32 ms | +4% |
| p90 | 2.02 ms | **1.62 ms** | −20% |
| p95 | 2.75 ms | **1.84 ms** | −33% |
| max | 83.74 ms | **49.73 ms** | −41% |

Both runs served 100% of requests with no errors.

## What these numbers actually say

**The cache buys about 30%, not 10×.** Worth stating plainly, because the
opposite is what a benchmark section usually claims.

The reason is in the first table: with the index, the resolve query costs
0.077 ms. The rest of a 1.4 ms response is HTTP parsing, the JVM, the servlet
stack and the loopback. Replacing a 0.08 ms database call with a 0.1 ms Redis
call cannot produce a large factor — there is nothing large to remove.

**The interesting column is the tail, not the median.** At 2 000 rps the
medians are within noise of each other while p95 improves by a third and the
worst case by 41%. That is the shape of the benefit: the cache is not making
the typical request much faster, it is removing the slow ones, where a request
happened to wait on a connection from the pool or on the database doing
something else.

**What this setup cannot show** is the reason to cache at scale: taking read
load off the database. Every request here is one query against a 27 MB table
that fits entirely in memory on an otherwise idle machine. With the database
on another host, under real traffic, serving other queries, the same 0.08 ms
becomes milliseconds of network and contention, and the cache stops being a
30% improvement.

The honest summary is that this hardware cannot demonstrate the case the cache
exists for. It demonstrates that the cache is not a regression and that it
flattens the tail, and those are the claims made.

## Reproducing

```bash
docker compose up -d postgres redis
docker exec -i trimly-postgres psql -U trimly -d trimly -q < benchmark/seed.sql

# codes the load test will hit
docker exec trimly-postgres psql -U trimly -d trimly -tAc \
  "select code from link order by random() limit 10000" > benchmark/out/codes.txt

./mvnw -q -DskipTests package

# one run per configuration
java -jar target/trimly-0.1.0-SNAPSHOT.jar --trimly.cache.enabled=false
java -jar target/trimly-0.1.0-SNAPSHOT.jar

docker run --rm \
  -v "$PWD/benchmark/out/codes.txt:/codes.txt:ro" \
  -v "$PWD/benchmark/redirect.js:/script.js:ro" \
  -e BASE_URL=http://host.docker.internal:8080 -e RATE=2000 -e DURATION=20s \
  --add-host=host.docker.internal:host-gateway \
  grafana/k6 run /script.js
```

Rate limiting is disabled for these runs; the load generator is a single
client and would otherwise be throttled after 20 requests.

## Notes on the method

**Constant arrival rate, not a fixed number of virtual users.** With virtual
users each one waits for its response before sending the next, so offered load
falls as the service slows down and a slow build produces numbers close to a
fast one. A fixed arrival rate keeps the pressure constant and lets latency
move.

**`redirects: 0`.** Following the redirect would measure example.com.

**Real codes, not random ones.** Random codes would exercise the
negative-cache path, which is a different question.

**One machine, one run each.** These are indicative, not rigorous: no repeated
trials, no confidence intervals, client and server sharing a CPU. Enough to
compare two configurations of the same build, not enough to publish.
