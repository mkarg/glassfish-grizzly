# Minimal reproducer: "UNEXPECTED NULL RECORD" (Grizzly 5.0.3)

Reproduces the warning logged by `org.glassfish.grizzly.http2.Http2SessionOutputSink.flushOutputQueue()`
when an HTTP/2 session is terminated while a record is pending in the session output queue
(TaskQueue `spaceInBytes` vs. record-count desync). See payara/Payara#8552.

## Run (JDK 21+, Maven)

    mvn -q compile exec:java

Expected:

    WARNING: UNEXPECTED NULL RECORD. Queue-size: 45 byteToTransfer=0 queueSizeToFree=0 queueSize=45

## Files

- `Repro.java` — the whole minimal reproducer (single file, ~55 lines)
- `pom.xml` — only dependency: `org.glassfish.grizzly:grizzly-http2:5.0.3`
- `UnexpectedNullRecordRepro.java` — older verbose variant, not needed
