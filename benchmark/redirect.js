// Load test for the redirect path.
//
//   docker run --rm -i --network host -e BASE_URL=http://localhost:8080 \
//     grafana/k6 run - < benchmark/redirect.js
//
// Codes are read from a file the seeding script writes, so the test exercises
// links that exist. Hitting random codes would measure the negative-cache path
// instead, which is a different question.

import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const codes = new SharedArray('codes', () =>
    open('/codes.txt').split('\n').filter((line) => line.length > 0));

const redirectDuration = new Trend('redirect_duration', true);

export const options = {
    scenarios: {
        redirect: {
            executor: 'constant-arrival-rate',
            // A fixed arrival rate, not a fixed number of users. With virtual
            // users the offered load drops as the service slows down, so a
            // slower build looks deceptively similar to a fast one.
            rate: Number(__ENV.RATE || 500),
            timeUnit: '1s',
            duration: __ENV.DURATION || '30s',
            preAllocatedVUs: 50,
            maxVUs: 400,
        },
    },
    thresholds: {
        // Recorded rather than enforced: this run exists to produce numbers,
        // and a failing threshold would hide them behind a non-zero exit.
        http_req_failed: ['rate<0.01'],
    },
};

export default function () {
    const code = codes[Math.floor(Math.random() * codes.length)];

    const response = http.get(`${BASE_URL}/${code}`, {
        redirects: 0, // measure the redirect itself, not the destination
        tags: { name: 'redirect' },
    });

    redirectDuration.add(response.timings.duration);

    check(response, {
        'is 302': (r) => r.status === 302,
    });
}
