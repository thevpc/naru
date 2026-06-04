package net.thevpc.naru.api.util;

import java.util.Arrays;
import java.util.NoSuchElementException;

/**
 * Sorted set of primitive longs, ordered by value.
 * O(log n) lookup, O(n) insert due to array shift.
 * Optimized for small-to-medium sets with frequent ordered iteration.
 * Not thread-safe — synchronize externally.
 */
public class NaruLongSortedSet {

    private long[] data;
    private int size;

    public NaruLongSortedSet() {
        this(4);
    }

    public NaruLongSortedSet(int initialCapacity) {
        data = new long[Math.max(1, initialCapacity)];
    }

    public boolean add(long value) {
        int idx = Arrays.binarySearch(data, 0, size, value);
        if (idx >= 0) return false;
        int insert = -(idx + 1);
        ensureCapacity();
        System.arraycopy(data, insert, data, insert + 1, size - insert);
        data[insert] = value;
        size++;
        return true;
    }

    public boolean contains(long value) {
        return Arrays.binarySearch(data, 0, size, value) >= 0;
    }

    public boolean remove(long value) {
        int idx = Arrays.binarySearch(data, 0, size, value);
        if (idx < 0) return false;
        System.arraycopy(data, idx + 1, data, idx, size - idx - 1);
        size--;
        return true;
    }

    public long get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return data[index];
    }

    public long first() {
        if (size == 0) throw new NoSuchElementException();
        return data[0];
    }

    public long last() {
        if (size == 0) throw new NoSuchElementException();
        return data[size - 1];
    }

    /**
     * Returns a view of values >= fromValue, as indices into iteration.
     * Caller iterates from returned index forward.
     */
    public int tailIndex(long fromValue) {
        int idx = Arrays.binarySearch(data, 0, size, fromValue);
        return idx >= 0 ? idx : -(idx + 1);
    }

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }

    public long[] toArray() {
        return Arrays.copyOf(data, size);
    }

    /**
     * Iterate in sorted order from a given index.
     * Usage: for (int i = set.tailIndex(watermark); i < set.size(); i++) { set.get(i); }
     */

    private void ensureCapacity() {
        if (size == data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
    }

    @Override
    public String toString() {
        return Arrays.toString(toArray());
    }
}
