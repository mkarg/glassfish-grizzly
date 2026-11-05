# UnexpectedNullRecordRepro

Minimal reproducer for the "UNEXPECTED NULL RECORD" warning of
org.glassfish.grizzly.http2.Http2SessionOutputSink.flushOutputQueue() (Grizzly 5.0.3,
shipped e.g. in Payara 7.2026.10, see payara/Payara#8552).

## Run (JDK 21+)

    mvn dependency:copy -Dartifact=org.glassfish.grizzly:grizzly-http2:5.0.3 -DoutputDirectory=.
    mvn dependency:copy -Dartifact=org.glassfish.grizzly:grizzly-framework:5.0.3 -DoutputDirectory=.
    javac -cp grizzly-http2-5.0.3.jar:grizzly-framework-5.0.3.jar UnexpectedNullRecordRepro.java
    java  -cp .:grizzly-http2-5.0.3.jar:grizzly-framework-5.0.3.jar UnexpectedNullRecordRepro
