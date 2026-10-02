package edu.cit.garciano.channel;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
class ChannelStartupState {

    private final AtomicBoolean ready =
            new AtomicBoolean(false);

    boolean isReady() {
        return ready.get();
    }

    void markReady() {
        ready.set(true);
    }
}