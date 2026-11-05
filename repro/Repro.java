import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.asyncqueue.AsyncQueueRecord;
import org.glassfish.grizzly.asyncqueue.TaskQueue;
import org.glassfish.grizzly.http2.Http2SessionOutputSink;
import sun.misc.Unsafe;

public class Repro {

    static class Rec extends AsyncQueueRecord<WriteResult> {
        Rec() { super(null, null, null); }
        @Override public void notifyFailure(Throwable e) { }
        @Override public void recycle() { }
        @Override public WriteResult getCurrentResult() { return null; }
    }

    public static void main(String[] args) throws Exception {
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
        invoke(sink, "flushOutputQueue"); // -> WARNING: UNEXPECTED NULL RECORD. Queue-size: 45 ...
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
