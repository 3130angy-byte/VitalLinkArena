package org.example.online.server;

/**
 * "Cubeta de fichas": se aceptan hasta {@code burst} mensajes seguidos y se
 * recupera {@code perSecond} ficha por segundo. Evita que un jugador inunde
 * el chat o el servidor con mensajes (a propósito o por un cliente roto).
 */
final class RateLimiter {

    private final double perSecond;
    private final double burst;
    private double tokens;
    private long lastNanos = System.nanoTime();

    RateLimiter(double perSecond, double burst) {
        this.perSecond = perSecond;
        this.burst = burst;
        this.tokens = burst;
    }

    synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        tokens = Math.min(burst, tokens + (now - lastNanos) / 1e9 * perSecond);
        lastNanos = now;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }
}
