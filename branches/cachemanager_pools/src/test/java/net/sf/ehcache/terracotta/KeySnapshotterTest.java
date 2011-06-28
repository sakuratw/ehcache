package net.sf.ehcache.terracotta;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.store.TerracottaStore;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.text.ParseException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Alex Snaps
 */
public class KeySnapshotterTest {

    private static final String KEY = "Key";
    private static final long MAX_KEY = 10000;

    final CacheManager cacheManager = new CacheManager();

    @Test(expected = IllegalArgumentException.class)
    public void testThrowsIllegalArgumentExceptionOnZeroInterval() {
        new KeySnapshotter(createFakeTcClusteredCache(mock(TerracottaStore.class)), 0, false, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testThrowsIllegalArgumentExceptionOnNegativeInterval() {
        new KeySnapshotter(createFakeTcClusteredCache(mock(TerracottaStore.class)), -100, false, null);
    }

    @Test(expected = NullPointerException.class)
    public void testThrowsNullPointerExceptionOnNegativeInterval() {
        new KeySnapshotter(createFakeTcClusteredCache(mock(TerracottaStore.class)), 10, false, null);
    }

    @Test
    public void testOperatesOnDedicatedThread() throws ParseException, IOException {
        final RotatingSnapshotFile rotatingSnapshotFile = mock(RotatingSnapshotFile.class);
        final TerracottaStore mock = mock(TerracottaStore.class);
        KeySnapshotter snapshotter = new KeySnapshotter(createFakeTcClusteredCache(mock), 10, true, rotatingSnapshotFile);

        boolean found = false;
        for (Thread thread : getAllThreads()) {
            if (thread != null) {
                if(thread.getName().equals("KeySnapshotter for cache test")) {
                    found = true;
                }
            }
        }
        assertThat(found, is(true));
        snapshotter.dispose(true);
    }

    @Test
    public void testDisposesProperlyImmediately() throws BrokenBarrierException, InterruptedException, IOException {
        final RotatingSnapshotFile rotatingSnapshotFile = new RotatingSnapshotFile("dumps", "testingInterruptImmediate");
        final TerracottaStore mockedTcStore = mock(TerracottaStore.class);
        KeySnapshotter snapshotter = new KeySnapshotter(createFakeTcClusteredCache(mockedTcStore), 1, true, rotatingSnapshotFile);
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final Set mockedSet = mock(Set.class);
        final AtomicLong counter = new AtomicLong(0);
        when(mockedSet.iterator()).thenAnswer(new Answer<Iterator>() {
            public Iterator answer(final InvocationOnMock invocationOnMock) throws Throwable {
                return new Iterator<Object>() {

                    public boolean hasNext() {
                        return counter.get() < 10000;
                    }

                    public Object next() {
                        return "Key" + counter.getAndIncrement();
                    }

                    public void remove() {
                        //
                    }
                };
            }
        });
        when(mockedTcStore.getLocalKeys()).thenAnswer(new Answer<Set>() {
            public Set answer(final InvocationOnMock invocationOnMock) throws Throwable {
                barrier.await();
                return mockedSet;
            }
        });
        barrier.await();
        snapshotter.dispose(true);
        assertThat(counter.get() < 3, is(true));
        assertThat(rotatingSnapshotFile.readAll().size(), is(0));
    }

    @Test
    public void testDisposesProperlyButFinishes() throws BrokenBarrierException, InterruptedException, IOException {
        final RotatingSnapshotFile rotatingSnapshotFile = new RotatingSnapshotFile("dumps", "testingInterruptFinishes");
        final TerracottaStore mockedTcStore = mock(TerracottaStore.class);
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final Set mockedSet = mock(Set.class);
        final AtomicLong counter = new AtomicLong(0);
        when(mockedSet.iterator()).thenAnswer(new Answer<Iterator>() {
            public Iterator answer(final InvocationOnMock invocationOnMock) throws Throwable {
                return new Iterator<Object>() {

                    public boolean hasNext() {
                        return counter.get() < MAX_KEY;
                    }

                    public Object next() {
                        return KEY + counter.getAndIncrement();
                    }

                    public void remove() {
                        //
                    }
                };
            }
        });
        when(mockedTcStore.getLocalKeys()).thenAnswer(new Answer<Set>() {
            public Set answer(final InvocationOnMock invocationOnMock) throws Throwable {
                barrier.await();
                return mockedSet;
            }
        });
        KeySnapshotter snapshotter = new KeySnapshotter(createFakeTcClusteredCache(mockedTcStore), 1, true, rotatingSnapshotFile);
        barrier.await();
        snapshotter.dispose(false);
        assertThat(counter.get(), is(MAX_KEY));
        final Set<Object> objects = new HashSet<Object>(rotatingSnapshotFile.readAll());
        assertThat(objects.size(), is((int) MAX_KEY));
        for(int i = 0; i < MAX_KEY; i++) {
            objects.remove(KEY + i);
        }
        assertThat(objects.isEmpty(), is(true));
    }

    private Thread[] getAllThreads() {
        ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
        ThreadGroup parentGroup;
        while ((parentGroup = rootGroup.getParent()) != null) {
            rootGroup = parentGroup;
        }

        Thread[] threads = new Thread[rootGroup.activeCount()];
        rootGroup.enumerate(threads, true);
        return threads;
    }

    private Cache createFakeTcClusteredCache(TerracottaStore store) {
        final Cache cache = new Cache(new CacheConfiguration("test", 10));
        cacheManager.addCache(cache);
        try {
            final Field compoundStore = cache.getClass().getDeclaredField("compoundStore");
            compoundStore.setAccessible(true);
            compoundStore.set(cache, store);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return cache;
    }
}
