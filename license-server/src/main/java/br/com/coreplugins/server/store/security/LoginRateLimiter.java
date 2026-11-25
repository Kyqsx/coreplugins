package br.com.coreplugins.server.store.security;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** Limite simples em memória: no máximo {@code MAX} tentativas por chave a cada 10 minutos. */
@Component
public class LoginRateLimiter {

    private static final int MAX = 8;
    private static final long WINDOW_MILLIS = 10L * 60 * 1000;

    private final Map<String, Deque<Long>> hits = new HashMap<>();

    /** Conta uma tentativa. @return false se a chave já passou do limite */
    public synchronized boolean allow(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MILLIS) {
            q.pollFirst();
        }
        if (q.size() >= MAX) {
            return false;
        }
        q.addLast(now);
        if (hits.size() > 10_000) {
            hits.entrySet().removeIf(e -> e.getValue().isEmpty() || now - e.getValue().peekLast() > WINDOW_MILLIS);
        }
        return true;
    }

    /** Só consulta (não conta): true se a chave já estourou o limite. */
    public synchronized boolean blocked(String key) {
        Deque<Long> q = hits.get(key);
        if (q == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MILLIS) {
            q.pollFirst();
        }
        return q.size() >= MAX;
    }

    public synchronized void reset(String key) {
        hits.remove(key);
    }
}
