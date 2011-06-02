/**
 *  Copyright 2003-2010 Terracotta, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.sf.ehcache.store.disk;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheEntry;
import net.sf.ehcache.CacheException;
import net.sf.ehcache.Element;
import net.sf.ehcache.Status;
import net.sf.ehcache.concurrent.CacheLockProvider;
import net.sf.ehcache.concurrent.LockType;
import net.sf.ehcache.concurrent.Sync;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.CacheConfigurationListener;
import net.sf.ehcache.pool.Pool;
import net.sf.ehcache.pool.PoolAccessor;
import net.sf.ehcache.pool.PoolableStore;
import net.sf.ehcache.pool.impl.UnboundedPool;
import net.sf.ehcache.store.AbstractStore;
import net.sf.ehcache.store.ElementValueComparator;
import net.sf.ehcache.store.Policy;
import net.sf.ehcache.writer.CacheWriterManager;

import java.io.File;
import java.io.IOException;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Implements a persistent-to-disk store.
 * <p>
 * All new elements are automatically scheduled for writing to disk.
 * 
 * @author Chris Dennis
 * @author Ludovic Orban
 */
public class DiskStore extends AbstractStore implements PoolableStore {

    private static final String AUTO_DISK_PATH_DIRECTORY_PREFIX = "ehcache_auto_created";
    private static final int FFFFCD7D = 0xffffcd7d;
    private static final int FIFTEEN = 15;
    private static final int TEN = 10;
    private static final int THREE = 3;
    private static final int SIX = 6;
    private static final int FOURTEEN = 14;
    private static final int SIXTEEN = 16;

    private static final int RETRIES_BEFORE_LOCK = 2;
    private static final int DEFAULT_INITIAL_CAPACITY = 16;
    private static final int DEFAULT_SEGMENT_COUNT = 64;
    private static final float DEFAULT_LOAD_FACTOR = 0.75f;

    private final DiskStorageFactory disk;
    private final Random rndm = new Random();
    private final Segment[] segments;
    private final int segmentShift;
    private final AtomicReference<Status> status = new AtomicReference<Status>(Status.STATUS_UNINITIALISED);

    private volatile CacheLockProvider lockProvider;
    private volatile Set<Object> keySet;
    private volatile PoolAccessor onHeapPoolAccessor;
    private volatile PoolAccessor onDiskPoolAccessor;


    protected DiskStore(DiskStorageFactory disk, Pool onHeapPool, Pool onDiskPool) {
        this.segments = new Segment[DEFAULT_SEGMENT_COUNT];
        this.segmentShift = Integer.numberOfLeadingZeros(segments.length - 1);
        this.onHeapPoolAccessor = onHeapPool.createPoolAccessor(this);
        this.onDiskPoolAccessor = onDiskPool.createPoolAccessor(this, new DiskSizeOfEngine());

        for (int i = 0; i < this.segments.length; ++i) {
            this.segments[i] = new Segment(DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR, disk, onHeapPoolAccessor, onDiskPoolAccessor);
        }

        this.disk = disk;
        this.disk.bind(this);
        status.set(Status.STATUS_ALIVE);
    }

    /**
     * Creates a persitent-to-disk store for the given cache, using the given disk path.
     *
     * @param cache cache that fronts this store
     * @param diskStorePath disk path to store data in
     * @param onHeapPool pool to track heap usage
     * @param onDiskPool pool to track disk usage
     * @return a fully initialized store
     */
    public static DiskStore create(Cache cache, String diskStorePath, Pool onHeapPool, Pool onDiskPool) {
        DiskStorageFactory disk = new DiskStorageFactory(cache, diskStorePath, cache.getCacheEventNotificationService());
        DiskStore store = new DiskStore(disk, onHeapPool, onDiskPool);
        cache.getCacheConfiguration().addConfigurationListener(new CacheConfigurationListenerAdapter(disk));
        return store;
    }

    /**
     * Creates a persitent-to-disk store for the given cache, using the given disk path.
     * Heap and disk usage are not tracked by the returned store.
     *
     * @param cache cache that fronts this store
     * @param diskStorePath disk path to store data in
     * @return a fully initialized store
     */
    public static DiskStore create(Cache cache, String diskStorePath) {
        return create(cache, diskStorePath, new UnboundedPool(), new UnboundedPool());
    }

    /**
     * Generates a unique directory name for use in automatically creating a diskStorePath where there is a conflict.
     *
     * @return a path consisting of {@link #AUTO_DISK_PATH_DIRECTORY_PREFIX} followed by "_" followed by the current
     *         time as a long e.g. ehcache_auto_created_1149389837006
     */
    public static String generateUniqueDirectory() {
        return DiskStore.AUTO_DISK_PATH_DIRECTORY_PREFIX + "_" + System.currentTimeMillis();
    }

    public void readLock(Object key) {
        int hash = key == null ? 0 : hash(key.hashCode());
        segmentFor(hash).readLock().lock();
    }

    public void readUnlock(Object key) {
        int hash = key == null ? 0 : hash(key.hashCode());
        segmentFor(hash).readLock().unlock();
    }

    public void writeLock(Object key) {
        int hash = key == null ? 0 : hash(key.hashCode());
        segmentFor(hash).writeLock().lock();
    }

    public void writeUnlock(Object key) {
        int hash = key == null ? 0 : hash(key.hashCode());
        segmentFor(hash).writeLock().unlock();
    }

    public void readLock() {
        for (Segment segment : segments) {
            segment.readLock().lock();
        }
    }

    public void readUnlock() {
        for (Segment segment : segments) {
            segment.readLock().unlock();
        }
    }

    public void writeLock() {
        for (Segment segment : segments) {
            segment.writeLock().lock();
        }
    }

    public void writeUnlock() {
        for (Segment segment : segments) {
            segment.writeLock().unlock();
        }
    }

    /**
     *
     */
    private static class CacheConfigurationListenerAdapter implements CacheConfigurationListener {

        private final DiskStorageFactory disk;

        public CacheConfigurationListenerAdapter(DiskStorageFactory disk) {
            this.disk = disk;
        }

        public void timeToIdleChanged(long oldTimeToIdle, long newTimeToIdle) {
            // no-op
        }

        public void timeToLiveChanged(long oldTimeToLive, long newTimeToLive) {
            // no-op
        }

        public void diskCapacityChanged(int oldCapacity, int newCapacity) {
            disk.setOnDiskCapacity(newCapacity);
        }

        public void memoryCapacityChanged(int oldCapacity, int newCapacity) {
            // no-op
        }

        public void loggingChanged(boolean oldValue, boolean newValue) {
            // no-op
        }

        public void registered(CacheConfiguration config) {
            // no-op
        }

        public void deregistered(CacheConfiguration config) {
            // no-op
        }
    }

    
    /**
     * {@inheritDoc}
     */
    public boolean bufferFull() {
        return disk.bufferFull();
    }

    /**
     * {@inheritDoc}
     */
    public boolean containsKeyInMemory(Object key) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean containsKeyOffHeap(Object key) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean containsKeyOnDisk(Object key) {
        return disk.isOnDisk(unretrievedGet(key));
    }

    /**
     * {@inheritDoc}
     */
    public void expireElements() {
        disk.expireElements();
    }

    /**
     * {@inheritDoc}
     */
    public void flush() throws IOException {
        disk.flush();
    }

    /**
     * {@inheritDoc}
     */
    public Policy getInMemoryEvictionPolicy() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public int getInMemorySize() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    public long getInMemorySizeInBytes() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    public int getOffHeapSize() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    public long getOffHeapSizeInBytes() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    public int getOnDiskSize() {
        return disk.getOnDiskSize();
    }

    /**
     * {@inheritDoc}
     */
    public long getOnDiskSizeInBytes() {
        return disk.getOnDiskSizeInBytes();
    }

    /**
     * {@inheritDoc}
     */
    public int getTerracottaClusteredSize() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    public void setInMemoryEvictionPolicy(Policy policy) {
    }

    /**
     * Return a reference to the data file backing this store.
     */
    public File getDataFile() {
        return disk.getDataFile();
    }

    /**
     * Return a reference to the index file for this store.
     */
    public File getIndexFile() {
        return disk.getIndexFile();
    }

    /**
     * {@inheritDoc}
     */
    public Object getMBean() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public boolean put(Element element) {
        if (element == null) {
            return false;
        } else {
            Object key = element.getObjectKey();
            int hash = hash(key.hashCode());
            Element oldElement = segmentFor(hash).put(key, hash, element, false);
            return oldElement == null;
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean putWithWriter(Element element, CacheWriterManager writerManager) {
        boolean newPut = put(element);
        if (writerManager != null) {
          try {
            writerManager.put(element);
          } catch (RuntimeException e) {
            throw new CacheException(e);
          }
        }
        return newPut;
    }

    /**
     * {@inheritDoc}
     */
    public Element get(Object key) {
        if (key == null) {
            return null;
        }

        int hash = hash(key.hashCode());
        return segmentFor(hash).get(key, hash);
    }

    /**
     * {@inheritDoc}
     */
    public Element getQuiet(Object key) {
        return get(key);
    }

    /**
     * Return the unretrieved (undecoded) value for this key
     *
     * @param key key to lookup
     * @return Element or ElementSubstitute
     */
    public Object unretrievedGet(Object key) {
        if (key == null) {
            return null;
        }

        int hash = hash(key.hashCode());
        return segmentFor(hash).unretrievedGet(key, hash);
    }

    /**
     * Put the given encoded element directly into the store
     */
    public boolean putRawIfAbsent(Object key, Object encoded) {
        int hash = hash(key.hashCode());
        return segmentFor(hash).putRawIfAbsent(key, hash, encoded);
    }

    /**
     * {@inheritDoc}
     */
    public List getKeys() {
        return new ArrayList(keySet());
    }

    /**
     * Get a set view of the keys in this store
     */
    public Set<Object> keySet() {
        if (keySet != null) {
            return keySet;
        } else {
            keySet = new KeySet();
            return keySet;
        }
    }

    /**
     * {@inheritDoc}
     */
    public Element remove(Object key) {
        if (key == null) {
            return null;
        }

        int hash = hash(key.hashCode());
        return segmentFor(hash).remove(key, hash, null, null);
    }

    /**
     * {@inheritDoc}
     */
    public Element removeWithWriter(Object key, CacheWriterManager writerManager) {
        Element removed = remove(key);
        if (writerManager != null) {
            writerManager.remove(new CacheEntry(key, removed));
        }
        return removed;
    }

    /**
     * {@inheritDoc}
     */
    public void removeAll() {
        for (Segment s : segments) {
            s.clear();
        }
    }

    /**
     * {@inheritDoc}
     */
    public void dispose() {
        if (status.compareAndSet(Status.STATUS_ALIVE, Status.STATUS_SHUTDOWN)) {
            disk.unbind();
            onHeapPoolAccessor.unlink();
            onDiskPoolAccessor.unlink();
        }
    }

    /**
     * {@inheritDoc}
     */
    public int getSize() {
        final Segment[] segs = this.segments;
        long size = -1;
        // Try a few times to get accurate count. On failure due to
        // continuous async changes in table, resort to locking.
        for (int k = 0; k < RETRIES_BEFORE_LOCK; ++k) {
            size = volatileSize(segs);
            if (size >= 0) {
                break;
            }
        }
        if (size < 0) {
            // Resort to locking all segments
            size = lockedSize(segs);
        }
        if (size > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        } else {
            return (int) size;
        }
    }

    private static long volatileSize(Segment[] segs) {
        int[] mc = new int[segs.length];
        long check = 0;
        long sum = 0;
        int mcsum = 0;
        for (int i = 0; i < segs.length; ++i) {
            sum += segs[i].count;
            mc[i] = segs[i].modCount;
            mcsum += mc[i];
        }
        if (mcsum != 0) {
            for (int i = 0; i < segs.length; ++i) {
                check += segs[i].count;
                if (mc[i] != segs[i].modCount) {
                    return -1;
                }
            }
        }
        if (check == sum) {
            return sum;
        } else {
            return -1;
        }
    }

    private static long lockedSize(Segment[] segs) {
        long size = 0;
        for (int i = 0; i < segs.length; ++i) {
            segs[i].readLock().lock();
        }
        for (int i = 0; i < segs.length; ++i) {
            size += segs[i].count;
        }
        for (int i = 0; i < segs.length; ++i) {
            segs[i].readLock().unlock();
        }

        return size;
    }

    /**
     * {@inheritDoc}
     */
    public Status getStatus() {
        return status.get();
    }

    public boolean evictFromOnHeap(int count, long size) {
        // evicting from disk also frees up heap
        return disk.evict(count) == count;
    }

    public boolean evictFromOffHeap(int count, long size) {
        return false;
    }

    public boolean evictFromOnDisk(int count, long size) {
        return disk.evict(count) == count;
    }

    public float getApproximateDiskHitRate() {
        float sum = 0;
        for (Segment s : segments) {
            sum += s.getDiskHitRate();
        }
        return sum;
    }

    public float getApproximateDiskMissRate() {
        float sum = 0;
        for (Segment s : segments) {
            sum += s.getDiskMissRate();
        }
        return sum;
    }

    public float getApproximateHeapHitRate() {
        return 0;
    }

    public float getApproximateHeapMissRate() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    public boolean containsKey(Object key) {
        int hash = hash(key.hashCode());
        return segmentFor(hash).containsKey(key, hash);
    }

    /**
     * {@inheritDoc}
     */
    public Object getInternalContext() {
        if (lockProvider != null) {
            return lockProvider;
        } else {
            lockProvider = new LockProvider();
            return lockProvider;
        }
    }

    /**
     * {@inheritDoc}
     */
    public Element putIfAbsent(Element element) throws NullPointerException {
        Object key = element.getObjectKey();
        int hash = hash(key.hashCode());
        return segmentFor(hash).put(key, hash, element, true);
    }

    /**
     * {@inheritDoc}
     */
    public Element removeElement(Element element, ElementValueComparator comparator) throws NullPointerException {
        Object key = element.getObjectKey();
        int hash = hash(key.hashCode());
        return segmentFor(hash).remove(key, hash, element, comparator);
    }

    /**
     * {@inheritDoc}
     */
    public boolean replace(Element old, Element element, ElementValueComparator comparator)
            throws NullPointerException, IllegalArgumentException {
        Object key = element.getObjectKey();
        int hash = hash(key.hashCode());
        return segmentFor(hash).replace(key, hash, old, element, comparator);
    }

    /**
     * {@inheritDoc}
     */
    public Element replace(Element element) throws NullPointerException {
        Object key = element.getObjectKey();
        int hash = hash(key.hashCode());
        return segmentFor(hash).replace(key, hash, element);
    }

    /**
     * Atomically switch (CAS) the <code>expect</code> representation of this element for the
     * <code>fault</code> representation.
     * <p>
     * A successful switch will return <code>true</code>, and free the replaced element/element-proxy.
     * A failed switch will return <code>false</code> and free the element/element-proxy which was not
     * installed.
     *
     * @param key key to which this element (proxy) is mapped
     * @param expect element (proxy) expected
     * @param fault element (proxy) to install
     * @return <code>true</code> if <code>fault</code> was installed
     */
    public boolean fault(Object key, Object expect, Object fault) {
        int hash = hash(key.hashCode());
        boolean success = segmentFor(hash).fault(key, hash, expect, fault);
        return success;
    }

    /**
     * Remove the matching mapping. The evict method does referential comparison
     * of the unretrieved substitute against the argument value.
     *
     * @param key key to match against
     * @param substitute optional value to match against
     * @return <code>true</code> on a successful remove
     */
    public boolean evict(Object key, Object substitute) {
        return evictElement(key, substitute) != null;
    }

    /**
     * Remove the matching mapping. The evict method does referential comparison
     * of the unretrieved substitute against the argument value.
     *
     * @param key key to match against
     * @param substitute optional value to match against
     * @return the evicted element on a successful remove
     */
    public Element evictElement(Object key, Object substitute) {
        int hash = hash(key.hashCode());
        Element evicted = segmentFor(hash).evict(key, hash, substitute);
        return evicted;
    }

    /**
     * Select a random sample of elements generated by the supplied factory.
     *
     * @param <T> type of the elements or element substitutes
     * @param factory generator of the given type
     * @param sampleSize minimum number of elements to return
     * @param keyHint a key on which we are currently working
     * @return list of sampled elements/element substitute
     */
    public <T> List<T> getRandomSample(ElementSubstituteFilter factory, int sampleSize, Object keyHint) {
        ArrayList<T> sampled = new ArrayList<T>(sampleSize);

        // pick a random starting point in the map
        int randomHash = rndm.nextInt();

        final int segmentStart;
        if (keyHint == null) {
            segmentStart = (randomHash >>> segmentShift);
        } else {
            segmentStart = (hash(keyHint.hashCode()) >>> segmentShift);
        }

        int segmentIndex = segmentStart;
        do {
            segments[segmentIndex].addRandomSample(factory, sampleSize, sampled, randomHash);
            if (sampled.size() >= sampleSize) {
                break;
            }
            // move to next segment
            segmentIndex = (segmentIndex + 1) & (segments.length - 1);
        } while (segmentIndex != segmentStart);

        return sampled;
    }

    private static int hash(int hash) {
        int spread = hash;
        spread += (spread << FIFTEEN ^ FFFFCD7D);
        spread ^= spread >>> TEN;
        spread += (spread << THREE);
        spread ^= spread >>> SIX;
        spread += (spread << 2) + (spread << FOURTEEN);
        return (spread ^ spread >>> SIXTEEN);
    }

    private Segment segmentFor(int hash) {
        return segments[hash >>> segmentShift];
    }

    /**
     * Key set implementation for the DiskStore
     */
    final class KeySet extends AbstractSet<Object> {

        /**
         * {@inheritDoc}
         */
        @Override
        public Iterator<Object> iterator() {
            return new KeyIterator();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int size() {
            return DiskStore.this.getSize();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean contains(Object o) {
            return DiskStore.this.containsKey(o);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean remove(Object o) {
            return DiskStore.this.remove(o) != null;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void clear() {
            DiskStore.this.removeAll();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object[] toArray() {
            Collection<Object> c = new ArrayList<Object>();
            for (Object object : this) {
                c.add(object);
            }
            return c.toArray();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public <T> T[] toArray(T[] a) {
            Collection<Object> c = new ArrayList<Object>();
            for (Object object : this) {
                c.add(object);
            }
            return c.toArray(a);
        }
    }

    /**
     * LockProvider implementation that uses the segment locks.
     */
    private class LockProvider implements CacheLockProvider {

        /**
         * {@inheritDoc}
         */
        public Sync getSyncForKey(Object key) {
            int hash = key == null ? 0 : hash(key.hashCode());
            return new ReadWriteLockSync(segmentFor(hash));
        }
    }

    /**
     * Superclass for all store iterators.
     */
    abstract class HashIterator {
        private int segmentIndex;
        private Iterator<HashEntry> currentIterator;

        /**
         * Constructs a new HashIterator
         */
        HashIterator() {
            segmentIndex = segments.length;

            while (segmentIndex > 0) {
                segmentIndex--;
                currentIterator = segments[segmentIndex].hashIterator();
                if (currentIterator.hasNext()) {
                    return;
                }
            }
        }

        /**
         * {@inheritDoc}
         */
        public boolean hasNext() {
            if (this.currentIterator == null) {
                return false;
            }

            if (this.currentIterator.hasNext()) {
                return true;
            } else {
                while (segmentIndex > 0) {
                    segmentIndex--;
                    currentIterator = segments[segmentIndex].hashIterator();
                    if (currentIterator.hasNext()) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Returns the next hash-entry - called by subclasses
         *
         * @return next HashEntry
         */
        protected HashEntry nextEntry() {
            HashEntry item = null;

            if (currentIterator == null) {
                return null;
            }

            if (currentIterator.hasNext()) {
                return currentIterator.next();
            } else {
                while (segmentIndex > 0) {
                    segmentIndex--;
                    currentIterator = segments[segmentIndex].hashIterator();
                    if (currentIterator.hasNext()) {
                        return currentIterator.next();
                    }
                }
            }
            return null;
        }

        /**
         * {@inheritDoc}
         */
        public void remove() {
            currentIterator.remove();
        }

        /**
         * Get the current segment index of this iterator
         *
         * @return the current segment index
         */
        int getCurrentSegmentIndex() {
            return segmentIndex;
        }
    }

    /**
     * Iterator over the store key set.
     */
    private final class KeyIterator extends HashIterator implements Iterator<Object> {
        /**
         * {@inheritDoc}
         */
        public Object next() {
            return super.nextEntry().key;
        }
    }

    /**
     * Sync implementation that wraps the segment locks
     */
    private final static class ReadWriteLockSync implements Sync {

        private final ReentrantReadWriteLock lock;

        private ReadWriteLockSync(ReentrantReadWriteLock lock) {
            this.lock = lock;
        }

        /**
         * {@inheritDoc}
         */
        public void lock(LockType type) {
            switch (type) {
            case READ:
                lock.readLock().lock();
                break;
            case WRITE:
                lock.writeLock().lock();
                break;
            default:
                throw new IllegalArgumentException("We don't support any other lock type than READ or WRITE!");
            }
        }

        /**
         * {@inheritDoc}
         */
        public boolean tryLock(LockType type, long msec) throws InterruptedException {
            switch (type) {
            case READ:
                return lock.readLock().tryLock(msec, TimeUnit.MILLISECONDS);
            case WRITE:
                return lock.writeLock().tryLock(msec, TimeUnit.MILLISECONDS);
            default:
                throw new IllegalArgumentException("We don't support any other lock type than READ or WRITE!");
            }
        }

        /**
         * {@inheritDoc}
         */
        public void unlock(LockType type) {
            switch (type) {
            case READ:
                lock.readLock().unlock();
                break;
            case WRITE:
                lock.writeLock().unlock();
                break;
            default:
                throw new IllegalArgumentException("We don't support any other lock type than READ or WRITE!");
            }
        }

        public boolean isHeldByCurrentThread(LockType type) {
            switch (type) {
            case READ:
                throw new UnsupportedOperationException("Querying of read lock is not supported.");
            case WRITE:
                return lock.isWriteLockedByCurrentThread();
            default:
                throw new IllegalArgumentException("We don't support any other lock type than READ or WRITE!");
            }
        }

    }
}
