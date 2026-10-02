package edu.cit.garciano.channel;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Component
class ChannelMarketplaceLock {

    /*
     * One marketplace-critical operation at a time.
     *
     * Normal Tiangge feed work has priority over
     * background backorder resolution.
     */
    private final ReentrantLock lock =
            new ReentrantLock(true);

    /*
     * Start TRUE intentionally.
     *
     * Until the first successful feed poll proves that
     * we are caught up, background backorder resolution
     * must stay out of the way.
     */
    private final AtomicBoolean feedBacklogActive =
            new AtomicBoolean(true);

    /*
     * Number of deadline-sensitive feed operations that
     * are currently waiting for the marketplace lock.
     */
    private final AtomicInteger feedWaiters =
            new AtomicInteger(0);

    boolean setFeedBacklogActive(
            boolean active
    ) {

        boolean previous =
                feedBacklogActive.getAndSet(
                        active
                );

        return previous != active;
    }

    boolean isFeedBacklogActive() {

        return feedBacklogActive.get();
    }

    /*
     * Used by ORDER_PLACED and ORDER_CANCELLED.
     *
     * Feed work is deadline-sensitive, so it is allowed
     * to wait until the current critical operation ends.
     */
    void lockForFeed() {

        feedWaiters.incrementAndGet();

        try {

            lock.lock();

        } finally {

            feedWaiters.decrementAndGet();
        }
    }

    /*
     * Used only by background backorder resolution.
     *
     * Background work must never wait in front of the
     * normal Tiangge feed.
     */
    boolean tryLockForBackground() {

        if (
                feedBacklogActive.get()
                        ||
                feedWaiters.get() > 0
        ) {

            return false;
        }

        if (!lock.tryLock()) {

            return false;
        }

        /*
         * Recheck after acquiring the lock because feed
         * state may have changed between the first check
         * and tryLock().
         */
        if (
                feedBacklogActive.get()
                        ||
                feedWaiters.get() > 0
        ) {

            lock.unlock();

            return false;
        }

        return true;
    }

    void unlock() {

        lock.unlock();
    }
}