package android.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.TreeMap;

/**
 * Host-only sorted-key stand-in for the operations used by FontScaleConverterFactory.
 * This is not an Android implementation or runtime-parity fixture.
 */
public class SparseArray<E> implements Cloneable {
    private final TreeMap<Integer, E> values = new TreeMap<>();

    public E get(int key) {
        return values.get(key);
    }

    public void put(int key, E value) {
        values.put(key, value);
    }

    public int indexOfKey(int key) {
        return Collections.binarySearch(new ArrayList<>(values.keySet()), key);
    }

    public int size() {
        return values.size();
    }

    public int keyAt(int index) {
        return new ArrayList<>(values.keySet()).get(index);
    }

    public E valueAt(int index) {
        return values.get(keyAt(index));
    }

    @Override
    public SparseArray<E> clone() {
        SparseArray<E> result = new SparseArray<>();
        result.values.putAll(values);
        return result;
    }
}
