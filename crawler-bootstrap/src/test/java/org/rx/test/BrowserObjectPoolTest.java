package org.rx.test;

import org.junit.jupiter.api.Test;
import org.rx.core.ObjectPool;
import org.rx.exception.InvalidException;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BrowserObjectPoolTest {
    @Test
    public void objectPoolBorrowRecycleAndDuplicateRecycle() throws TimeoutException {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger passivated = new AtomicInteger();
        ObjectPool<TestBrowser> pool = new ObjectPool<>(
                1,
                2,
                () -> new TestBrowser(created.incrementAndGet()),
                p -> !p.closed,
                null,
                p -> passivated.incrementAndGet());
        try {
            pool.setName("browser-test");
            pool.setBorrowTimeout(1000);
            pool.setIdleTimeout(0);

            assertEquals(1, pool.size());
            assertEquals(1, pool.idleSize());
            assertEquals(1, passivated.get());

            TestBrowser first = pool.borrow();
            assertEquals(0, pool.idleSize());
            assertEquals(1, pool.size() - pool.idleSize());

            pool.recycle(first);
            assertEquals(1, pool.idleSize());
            assertEquals(2, passivated.get());
            assertThrows(InvalidException.class, () -> pool.recycle(first));

            TestBrowser again = pool.borrow();
            assertEquals(first.id, again.id);
        } finally {
            pool.close();
        }
    }

    static final class TestBrowser implements AutoCloseable {
        final int id;
        boolean closed;

        TestBrowser(int id) {
            this.id = id;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
