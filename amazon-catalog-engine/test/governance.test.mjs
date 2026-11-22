import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { CircuitBreaker, CircuitState, RateLimitedError, RatePacer } from '../src/governance.mjs';

describe('Governance & CircuitBreaker', () => {
  test('circuit starts CLOSED and records success', async () => {
    const breaker = new CircuitBreaker();
    assert.equal(breaker.getState('US').circuit, CircuitState.CLOSED);

    const res = await breaker.execute('US', async () => 'ok');
    assert.equal(res, 'ok');
    assert.equal(breaker.getState('US').circuit, CircuitState.CLOSED);
  });

  test('trips immediately on HTTP 429', async () => {
    const breaker = new CircuitBreaker({ baseCooldownMs: 5000 });

    try {
      await breaker.execute('US', async () => {
        const err = new Error('Too many requests');
        err.statusCode = 429;
        throw err;
      });
      assert.fail('Should have thrown');
    } catch (err) {
      assert.equal(err.statusCode, 429);
    }

    // Now circuit should be OPEN
    assert.equal(breaker.getState('US').circuit, CircuitState.OPEN);

    // Next call should fail fast with RateLimitedError without executing function
    let executed = false;
    await assert.rejects(
      async () => {
        await breaker.execute('US', async () => {
          executed = true;
        });
      },
      RateLimitedError
    );
    assert.equal(executed, false, 'Should not execute task when circuit is OPEN');
  });

  test('RatePacer enforces minimum interval between calls', async () => {
    const pacer = new RatePacer({ minIntervalMs: 50 });
    const t0 = performance.now();
    await pacer.pace('US');
    await pacer.pace('US');
    const elapsed = performance.now() - t0;
    assert.ok(elapsed >= 40, `Elapsed ${elapsed}ms should be >= minIntervalMs`);
  });
});
