package com.sondertara.joya.utils;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;

/**
 * 大小写不敏感的 Set，替代 common-tara 的 LinkedCaseInsensitiveSet。
 * 基于 HashMap，key 转小写存储。
 *
 * @author huangxiaohu
 */
public final class LinkedCaseInsensitiveSet<E> extends AbstractSet<E> implements Set<E> {

    private final HashMap<String, E> map = new HashMap<>();

    public LinkedCaseInsensitiveSet() {
    }

    public LinkedCaseInsensitiveSet(Collection<? extends E> elements) {
        addAll(elements);
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return o != null && map.containsKey(o.toString().toLowerCase());
    }

    @Override
    public Iterator<E> iterator() {
        return map.values().iterator();
    }

    @Override
    public boolean add(E e) {
        if (e == null) {
            return false;
        }
        String key = e.toString().toLowerCase();
        if (map.containsKey(key)) {
            return false;
        }
        map.put(key, e);
        return true;
    }

    @Override
    public boolean remove(Object o) {
        if (o == null) {
            return false;
        }
        return map.remove(o.toString().toLowerCase()) != null;
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        boolean modified = false;
        for (E e : c) {
            if (add(e)) {
                modified = true;
            }
        }
        return modified;
    }

    @Override
    public Object[] toArray() {
        return map.values().toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return map.values().toArray(a);
    }
}