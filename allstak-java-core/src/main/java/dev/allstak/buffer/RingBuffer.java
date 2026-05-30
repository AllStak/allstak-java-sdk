package dev.allstak.buffer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe bounded FIFO ring buffer. When full, the oldest item is evicted (tail-drop).
 */
public final class RingBuffer<T> {

    private final Object[] items;
    private final int capacity;
    private int head = 0;
    private int size = 0;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean droppedWarningEmitted = false;
    private final AtomicLong droppedCount = new AtomicLong();

    public RingBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.items = new Object[capacity];
    }

    public void add(T item) {
        lock.lock();
        try {
            if (size == capacity) {
                // Overwrite oldest (tail-drop)
                items[head] = item;
                head = (head + 1) % capacity;
                droppedCount.incrementAndGet();
                if (!droppedWarningEmitted) {
                    droppedWarningEmitted = true;
                }
            } else {
                int insertIndex = (head + size) % capacity;
                items[insertIndex] = item;
                size++;
            }
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    public List<T> drain() {
        lock.lock();
        try {
            List<T> result = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                int idx = (head + i) % capacity;
                result.add((T) items[idx]);
                items[idx] = null;
            }
            head = 0;
            size = 0;
            droppedWarningEmitted = false;
            return result;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return capacity;
    }

    public long droppedCount() {
        return droppedCount.get();
    }

    public boolean isAtCapacityThreshold() {
        lock.lock();
        try {
            return size >= (int) (capacity * 0.8);
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return size == 0;
        } finally {
            lock.unlock();
        }
    }
}
