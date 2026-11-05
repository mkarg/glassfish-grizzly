import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.asyncqueue.AsyncQueueRecord;
import org.glassfish.grizzly.asyncqueue.TaskQueue;
import org.glassfish.grizzly.http2.Http2SessionOutputSink;
import sun.misc.Unsafe;

/**
 * Minimal reproducer for the warning
 *   "UNEXPECTED NULL RECORD. Queue-size: {0} byteToTransfer={1} queueSizeToFree={2} queueSize={3}"
 * logged by org.glassfish.grizzly.http2.Http2SessionOutputSink.flushOutputQueue()
 * in Grizzly 5.0.3 (as shipped e.g. in Payara 7.2026.10, see payara/Payara#8552).
 *
 * Run with JDK 21+ (jars from Maven Central):
 *   mvn dependency:copy -Dartifact=org.glassfish.grizzly:grizzly-http2:5.0.3 -DoutputDirectory=.
 *   mvn dependency:copy -Dartifact=org.glassfish.grizzly:grizzly-framework:5.0.3 -DoutputDirectory=.
 *   javac -cp grizzly-http2-5.0.3.jar:grizzly-framework-5.0.3.jar UnexpectedNullRecordRepro.java
 *   java  -cp .:grizzly-http2-5.0.3.jar:grizzly-framework-5.0.3.jar UnexpectedNullRecordRepro
 *
 * Expected output (Part B) is exactly:
 *   WARNING: UNEXPECTED NULL RECORD. Queue-size: 45 byteToTransfer=0 queueSizeToFree=0 queueSize=45
 */
public class UnexpectedNullRecordRepro {

    static class Rec extends AsyncQueueRecord<WriteResult> {
        Rec() { super(null, null, null); }
        @Override public void notifyFailure(Throwable e) { }
        @Override public void recycle() { }
        @Override public WriteResult getCurrentResult() { return null; }
    }

    public static void main(String[] args) throws Exception {
        partA_taskQueueDesync();
        partB_realHttp2SessionOutputSink();
    }

    /**
     * Part A: shows the TaskQueue state desync directly, no reflection:
     * after onClose() the queue is empty but size()/isEmpty() still report reserved bytes.
     */
    static void partA_taskQueueDesync() {
        System.out.println("=== Part A: TaskQueue desync (grizzly-framework-5.0.3) ===");
        @SuppressWarnings("rawtypes")
        TaskQueue q = TaskQueue.createTaskQueue(() -> 65536);
        q.offer(new Rec());
        q.reserveSpace(45);            // Http2SessionOutputSink.writeDataDownStream(): offer(record); reserveSpace(dataSize)
        System.out.println("live:        size()=" + q.size() + " isEmpty()=" + q.isEmpty());
        q.onClose();                   // Http2SessionOutputSink.close() on session terminate -> drains records, never releases space
        System.out.println("after close: size()=" + q.size() + " isEmpty()=" + q.isEmpty() + " poll()=" + q.poll());
        q.offer(new Rec());            // a write after close: record is removed again immediately ...
        q.reserveSpace(45);            // ... but writeDataDownStream() still reserves the space
        System.out.println("write-after-close: size()=" + q.size() + " queueEmpty=" + q.getQueue().isEmpty());
    }

    /**
     * Part B: drives the REAL Http2SessionOutputSink (grizzly-http2-5.0.3) into its warning branch.
     * The instance is allocated without constructor (no full Http2Session needed); only the fields
     * used on the null-record path are set. flushOutputQueue() is the real private method.
     */
    static void partB_realHttp2SessionOutputSink() throws Exception {
        System.out.println("=== Part B: real Http2SessionOutputSink.flushOutputQueue() (grizzly-http2-5.0.3) ===");
        Field uf = Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        Unsafe unsafe = (Unsafe) uf.get(null);
        Http2SessionOutputSink sink = (Http2SessionOutputSink) unsafe.allocateInstance(Http2SessionOutputSink.class);

        Field qf = Http2SessionOutputSink.class.getDeclaredField("outputQueue");
        qf.setAccessible(true);
        @SuppressWarnings("rawtypes")
        TaskQueue queue = TaskQueue.createTaskQueue(() -> 65536);
        qf.set(sink, queue);

        Field wf = Http2SessionOutputSink.class.getDeclaredField("availConnectionWindowSize");
        wf.setAccessible(true);
        wf.set(sink, new AtomicInteger(65535)); // peer sent WINDOW_UPDATEs, window still open

        Field lf = Http2SessionOutputSink.class.getDeclaredField("writerLock");
        lf.setAccessible(true);
        lf.set(sink, new AtomicBoolean());

        Field tf = Http2SessionOutputSink.class.getDeclaredField("tmpFramesList");
        tf.setAccessible(true);
        tf.set(sink, new LinkedList<>());

        queue.offer(new Rec());
        queue.reserveSpace(45);        // pending 45-byte record ...

        Method close = Http2SessionOutputSink.class.getDeclaredMethod("close");
        close.setAccessible(true);
        close.invoke(sink);            // ... session terminated while record pending -> onClose() drains it

        Method flush = Http2SessionOutputSink.class.getDeclaredMethod("flushOutputQueue");
        flush.setAccessible(true);
        System.out.println("--- invoking real flushOutputQueue() (as a concurrent write / late WINDOW_UPDATE would) ---");
        flush.invoke(sink);            // -> logs the WARNING below
        System.out.println("--- done ---");
    }
}
