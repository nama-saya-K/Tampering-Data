package com.tamperdroid.proxy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * Process-wide singleton (Service + Activities run in the same process by
 * default) that both the proxy's worker threads and the UI talk to.
 *
 * - [history]: every exchange, for the plain "log" view (Tamper Data style).
 * - interceptEnabled: when true, requests are held via [holdForEdit] until
 *   the UI calls [release] (Burp Proxy "Intercept is on" behaviour).
 */
object ProxyBus {

    @Volatile var interceptEnabled: Boolean = false

    val history = CopyOnWriteArrayList<Exchange>()
    private val latches = ConcurrentHashMap<String, CountDownLatch>()

    interface Listener {
        fun onNewExchange(exchange: Exchange)
        fun onExchangeUpdated(exchange: Exchange)
        fun onHeldForIntercept(exchange: Exchange)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    fun recordRequest(exchange: Exchange) {
        history.add(0, exchange)
        listeners.forEach { it.onNewExchange(exchange) }
    }

    fun recordResponse(exchange: Exchange) {
        listeners.forEach { it.onExchangeUpdated(exchange) }
    }

    /**
     * Called from a proxy worker thread. Blocks that thread until the user
     * edits/forwards or drops the request from the UI. Returns the (possibly
     * mutated) exchange, or null if the user chose to drop the request.
     */
    fun holdForEdit(exchange: Exchange): Exchange? {
        exchange.held = true
        val latch = CountDownLatch(1)
        latches[exchange.id] = latch
        listeners.forEach { it.onHeldForIntercept(exchange) }
        latch.await() // blocks the proxy worker thread, not the UI thread
        latches.remove(exchange.id)
        return if (droppedIds.remove(exchange.id)) null else exchange
    }

    private val droppedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Called from the UI (DetailActivity) when the user taps "Forward". */
    fun release(exchange: Exchange) {
        exchange.held = false
        latches[exchange.id]?.countDown()
    }

    /** Called from the UI when the user taps "Drop". */
    fun drop(exchange: Exchange) {
        droppedIds.add(exchange.id)
        exchange.held = false
        latches[exchange.id]?.countDown()
    }

    fun clear() {
        history.clear()
    }
}
