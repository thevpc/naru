package net.thevpc.naru.api.util;

import java.util.Arrays;

/**
 * Hash set of primitive longs using open addressing with linear probing.
 * O(1) average insert/lookup, no ordering guarantee.
 * Optimized for small sets with frequent membership tests.
 * Not thread-safe — synchronize externally.
 */
public class NaruLongHashSet {

    private static final long EMPTY = Long.MIN_VALUE;

    private long[] table;
    private int size;
    private int threshold;

    public NaruLongHashSet() {
        this(4);
    }

    public NaruLongHashSet(int initialCapacity) {
        int cap = nextPowerOfTwo(Math.max(2, initialCapacity));
        table = new long[cap];
        Arrays.fill(table, EMPTY);
        threshold = cap * 3 / 4;
    }

    public boolean add(long value) {
        if (value == EMPTY) throw new IllegalArgumentException("Long.MIN_VALUE is reserved");
        if (size >= threshold) resize();
        int slot = slot(value);
        while (table[slot] != EMPTY) {
            if (table[slot] == value) return false;
            slot = (slot + 1) & (table.length - 1);
        }
        table[slot] = value;
        size++;
        return true;
    }

    public boolean contains(long value) {
        if (value == EMPTY) return false;
        int slot = slot(value);
        while (table[slot] != EMPTY) {
            if (table[slot] == value) return true;
            slot = (slot + 1) & (table.length - 1);
        }
        return false;
    }

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }

    public long[] toArray() {
        long[] result = new long[size];
        int i = 0;
        for (long v : table) {
            if (v != EMPTY) result[i++] = v;
        }
        return result;
    }

    private int slot(long value) {
        long h = value * 0x9e3779b97f4a7c15L;
        return (int) (h >>> (64 - Integer.numberOfTrailingZeros(table.length)))
                & (table.length - 1);
    }

    private void resize() {
        long[] old = table;
        int newCap = old.length * 2;
        table = new long[newCap];
        Arrays.fill(table, EMPTY);
        threshold = newCap * 3 / 4;
        size = 0;
        for (long v : old) {
            if (v != EMPTY) add(v);
        }
    }

    private static int nextPowerOfTwo(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }

    @Override
    public String toString() {
        return Arrays.toString(toArray());
    }
}
