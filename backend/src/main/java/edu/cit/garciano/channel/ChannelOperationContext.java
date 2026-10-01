package edu.cit.garciano.channel;

import org.springframework.stereotype.Component;

@Component
class ChannelOperationContext {

    private final ThreadLocal<String>
            currentTianggeOrder =
            new ThreadLocal<>();

    void begin(
            String tianggeOrderId
    ) {

        currentTianggeOrder.set(
                tianggeOrderId
        );
    }

    String currentOrderId() {

        return currentTianggeOrder.get();
    }

    void clear() {

        currentTianggeOrder.remove();
    }
}