package com.learning.lrucache.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe LRU Cache implementation using doubly-linked list and hash map.
 * Time Complexity: O(1) for both get and put operations.
 * Space Complexity: O(capacity)
 */
public class LRUCache<K, V> {
    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int capacity;
    private final ConcurrentHashMap<K, Node<K, V>> cache;
    private final Node<K, V> head; // Dummy head (most recently used)
    private final Node<K, V> tail; // Dummy tail (least recently used)
    private final ReentrantReadWriteLock lock;
    private int size;

    public LRUCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.cache = new ConcurrentHashMap<>();
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        this.head.next = tail;
        this.tail.prev = head;
        this.lock = new ReentrantReadWriteLock();
        this.size = 0;
    }

    /**
     * Get value by key. Moves the accessed node to front (most recently used).
     * Time Complexity: O(1)
     */
    public V get(K key) {
        lock.readLock().lock();
        try {
            Node<K, V> node = cache.get(key);
            if (node == null) {
                return null;
            }

            // Upgrade to write lock to move node
            lock.readLock().unlock();
            lock.writeLock().lock();
            try {
                moveToFront(node);
                return node.value;
            } finally {
                lock.readLock().lock();
                lock.writeLock().unlock();
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Put key-value pair. If cache is full, evicts LRU item.
     * Time Complexity: O(1)
     */
    public void put(K key, V value) {
        lock.writeLock().lock();
        try {
            Node<K, V> node = cache.get(key);

            if (node != null) {
                // Update existing node
                node.value = value;
                moveToFront(node);
            } else {
                // Add new node
                Node<K, V> newNode = new Node<>(key, value);

                if (size >= capacity) {
                    // Evict LRU (node before tail)
                    Node<K, V> lru = tail.prev;
                    removeNode(lru);
                    cache.remove(lru.key);
                    size--;
                }

                addToFront(newNode);
                cache.put(key, newNode);
                size++;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Remove a node from its current position in the list.
     */
    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    /**
     * Add node right after head (most recently used position).
     */
    private void addToFront(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    /**
     * Move existing node to front.
     */
    private void moveToFront(Node<K, V> node) {
        removeNode(node);
        addToFront(node);
    }

    public int size() {
        lock.readLock().lock();
        try {
            return size;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int capacity() {
        return capacity;
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            cache.clear();
            head.next = tail;
            tail.prev = head;
            size = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }
}
