# repro-verify — minimal Maven reproducer for "UNEXPECTED NULL RECORD" (Grizzly 5.0.3)

Reproduces payara/Payara#8552: `Http2SessionOutputSink.flushOutputQueue()` logs
`UNEXPECTED NULL RECORD. Queue-size: 45 byteToTransfer=0 queueSizeToFree=0 queueSize=45`
when an HTTP/2 session is terminated while a record is pending in the session
output queue (TaskQueue `spaceInBytes` vs. record-count desync).

Contents of this folder are the ONLY files needed:

    pom.xml
    src/test/java/repro/UnexpectedNullRecordTest.java

## Verify the bug

    mvn verify

On Grizzly 5.0.3 the test FAILS (this is intended — it names the bug):

    [ERROR] Tests run: 1, Failures: 1 <<< FAILURE! -- in repro.UnexpectedNullRecordTest
    AssertionFailedError: Grizzly 5.0.3 bug reproduced (payara/Payara#8552),
        flushOutputQueue() logged: UNEXPECTED NULL RECORD. Queue-size: {0} ...
    [ERROR] BUILD FAILURE

## Create the minimal-only branch `repro-verify` and push it

Run in a Vibe session that has this repository in its session scope
(e.g. a NEW session of the project after adding mkarg/glassfish-grizzly to it):

    git clone https://github.com/mkarg/glassfish-grizzly
    cd glassfish-grizzly
    git checkout repro/unexpected-null-record
    git checkout --orphan repro-verify
    git rm -rf .
    git checkout repro/unexpected-null-record -- repro-verify
    git mv repro-verify/pom.xml pom.xml
    mkdir -p src/test/java/repro
    git mv repro-verify/src/test/java/repro/UnexpectedNullRecordTest.java src/test/java/repro/
    git rm -rf repro-verify
    git add -A && git commit -m "Minimal repro: mvn verify fails on Grizzly 5.0.3 (UNEXPECTED NULL RECORD, payara/Payara#8552)"
    git push origin repro-verify

ZIP download of the minimal branch afterwards:

    https://github.com/mkarg/glassfish-grizzly/archive/refs/heads/repro-verify.zip
