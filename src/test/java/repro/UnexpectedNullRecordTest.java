package repro;

import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.asyncqueue.AsyncQueueRecord;
import org.glassfish.grizzly.asyncqueue.TaskQueue;
import org.glassfish.grizzly.http2.Http2SessionOutputSink;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * Reproduces payara/Payara#8552 against Grizzly 5.0.3:
 * Http2SessionOutputSink.flushOutputQueue() logs "UNEXPECTED NULL RECORD"
 * when the HTTP/2 session output queue is closed while a record is pending
 * (TaskQueue spaceInBytes vs. record-count desync).
 *
 * Run: mvn verify   ->   on Grizzly 5.0.3 this test FAILS and prints the warning.
 */
class UnexpectedNullRecordTest {

    static class Rec extends AsyncQueueRecord<WriteResult> {
        Rec() { super(null, null, null); }
        @Override public void notifyFailure(Throwable e) { }
        @Override public void recycle() { }
        @Override public WriteResult getCurrentResult() { return null; }
    }

    @Test
    void noUnexpectedNullRecordWarningAfterSessionClose() throws Exception {
        Logger logger = Logger.getLogger(Http2SessionOutputSink.class.getName());
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord r) { messages.add(r.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try {
            Field uf = Unsafe.class.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            Http2SessionOutputSink sink = (Http2SessionOutputSink) ((Unsafe) uf.get(null))
                    .allocateInstance(Http2SessionOutputSink.class);
            TaskQueue<Rec> q = TaskQueue.createTaskQueue(() -> 65536);
            set(sink, "outputQueue", q);
            set(sink, "availConnectionWindowSize", new AtomicInteger(65535)); // window still open
            set(sink, "writerLock", new AtomicBoolean());

            q.offer(new Rec());
            q.reserveSpace(45);               // pending record, as writeDataDownStream() does
            invoke(sink, "close");            // session terminate: onClose() drains queue, space stays reserved
            invoke(sink, "flushOutputQueue"); // real private method
        } finally {
            logger.removeHandler(handler);
        }
        for (String m : messages) {
            if (m.contains("UNEXPECTED NULL RECORD")) {
                fail("Grizzly 5.0.3 bug reproduced (payara/Payara#8552), flushOutputQueue() logged: " + m);
            }
        }
    }

    static void set(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    static void invoke(Object o, String name) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(o);
    }
}
