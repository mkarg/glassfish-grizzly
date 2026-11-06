# Issue for eclipse-ee4j/glassfish-grizzly — Title

Http2SessionOutputSink logs "UNEXPECTED NULL RECORD" after session termination with pending records (TaskQueue spaceInBytes vs. record-count desync)

# Issue Body

## Summary

Since Grizzly 5.0.x (observed with **5.0.3**, as shipped e.g. in Payara 7.2026.10 — see payara/Payara#8552), `Http2SessionOutputSink.flushOutputQueue()` can log:

```
[WARNING] [] [org.glassfish.grizzly.http2.Http2SessionOutputSink] [...]
  UNEXPECTED NULL RECORD. Queue-size: 45 byteToTransfer=0 queueSizeToFree=0 queueSize=45
```

## Root cause (verified against tag `5.0.3`)

1. `TaskQueue.size()` / `isEmpty()` return `spaceInBytes` — reserved **bytes**, not the record count (`modules/grizzly/src/main/java/org/glassfish/grizzly/asyncqueue/TaskQueue.java`).
2. `TaskQueue.onClose()` drains all records via `poll()` and fails them, but **never calls `releaseSpace()`** → after close, `spaceInBytes > 0` while the queue itself is empty.
3. `TaskQueue.offer()` on a closed queue adds and immediately removes the record (and fails it), but `Http2SessionOutputSink.writeDataDownStream()` (`modules/http2/src/main/java/org/glassfish/grizzly/http2/Http2SessionOutputSink.java:172-173`) unconditionally calls `reserveSpace(...)` afterwards → `spaceInBytes` keeps growing with no record behind it.
4. `flushOutputQueue()` (`Http2SessionOutputSink.java:190-212`) guards with `availWindow > 0 && !outputQueue.isEmpty() && writerLock.compareAndSet(...)`, snapshots `queueSize = outputQueue.size()`, then polls. `poll()` returns `currentElement` (via `getAndSet(null)`) or `queue.poll()` → `null` when both are empty → logs `UNEXPECTED NULL RECORD` and sets `breakNow`.

**Trigger:** `Http2Session.terminate(...)` → `closeConnectionAndOutputSink()` (`Http2Session.java:628-630`) → `outputSink.close()` → `onClose()` drains the queue while worker threads are still inside `writeDataDownStream()` / `onPeerWindowUpdate()` → `flushOutputQueue()`. Typical causes of terminate: peer disconnect (EOF on read → `Http2BaseFilter.java:183`), protocol error (`:178`), and since 5.0.3 the new async-write hard limit (#2324, `AbstractNIOAsyncQueueWriter.java:210-224` → `terminateSilently()`; the HTTP/2 session sink never checks `canWrite()`, see #2329).

## Impact

Warning only. After close the queue is dead: `offer()` fails records immediately, `notifyWritePossible()` fails handlers immediately (`isClosed` short-circuit), and `spaceInBytes` is a plain `int` holding no references (the records were removed and failed). No data loss, no hang, no memory leak. Practical effect: **one WARNING per post-close flush** → log spam when an application keeps writing after the peer disconnected.

## Reproducer

Minimal Maven project in this fork (only `pom.xml` + one test class):

- Branch: https://github.com/mkarg/glassfish-grizzly/tree/repro-verify
- ZIP download: https://github.com/mkarg/glassfish-grizzly/archive/refs/heads/repro-verify.zip

Run with JDK 21+: `mvn verify` → on Grizzly **5.0.3** the build **FAILS** (intended — the test names the bug):

```
WARNING: UNEXPECTED NULL RECORD. Queue-size: 45 byteToTransfer=0 queueSizeToFree=0 queueSize=45
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE! -- in repro.UnexpectedNullRecordTest
org.opentest4j.AssertionFailedError: Grizzly 5.0.3 bug reproduced (payara/Payara#8552), flushOutputQueue() logged: UNEXPECTED NULL RECORD. Queue-size: {0} byteToTransfer={1} queueSizeToFree={2} queueSize={3}
[ERROR] BUILD FAILURE
```

Independently reproduced by the reporter on Windows (JDK, de-DE locale). The test drives the real private `Http2SessionOutputSink.flushOutputQueue()`: queue a 45-byte record, `close()` (session terminate drains the queue but keeps the reserved space), then `flushOutputQueue()` → warning. On a fixed Grizzly the same test goes green (regression test).

A standalone single-file variant (no Maven, two jars from Maven Central) is on branch `repro/unexpected-null-record`: `repro/Repro.java`.

## Related

- #2016 — the warning was introduced as a diagnostic (commit `716b945`)
- #2314 — session terminated on EOF ("Unable to write GOAWAY. Terminating session.")
- #2329 — 5.0.3 hard limit vs. HTTP/2 flow control/backpressure
- payara/Payara#8552 — original report (Payara 7.2026.10)

# Comment to add after creation — Proposed fix

## Proposed fix — keep `spaceInBytes` synchronized with the actually queued records

1. **`TaskQueue.onClose(Throwable)`** (`modules/grizzly/src/main/java/org/glassfish/grizzly/asyncqueue/TaskQueue.java`): after the drain loop, reset the counter (`spaceInBytesUpdater.set(this, 0)`). The queue is closed and dead at that point, so `size()` / `isEmpty()` must reflect the drained state. → the post-close `flushOutputQueue()` guard `!outputQueue.isEmpty()` then fails and no warning is logged.
2. **`Http2SessionOutputSink.writeDataDownStream()`** (`modules/http2/.../Http2SessionOutputSink.java:172-173`): reserve space only if the record was actually queued — e.g. make `TaskQueue.offer()` return `boolean` (`false` when closed-and-removed) and change to `if (outputQueue.offer(record)) { outputQueue.reserveSpace(...); }`.
3. **Defensive:** in `flushOutputQueue()`, when `poll()` returns `null` on a closed queue, do not log WARNING (downgrade to FINE / skip); keep the WARNING only for a `null` record on an *open* queue, which should be impossible.

With (1) + (2) the byte counter can no longer desync from the record set, so the warning branch becomes unreachable in the close scenario and `UNEXPECTED NULL RECORD` disappears from logs of terminating HTTP/2 sessions.
