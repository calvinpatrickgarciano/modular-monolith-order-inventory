package edu.cit.garciano.channel;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class TianggeFeedPoller {

    private final TianggeFeedProcessor feedProcessor;
    private final ChannelStartupState startupState;

    TianggeFeedPoller(
            TianggeFeedProcessor feedProcessor,
            ChannelStartupState startupState
    ) {

        this.feedProcessor =
                feedProcessor;

        this.startupState =
                startupState;
    }

    @Scheduled(
            fixedDelayString =
                    "${channel.tiangge.feed-poll-ms:1000}",
            initialDelayString =
                    "${channel.tiangge.feed-initial-delay-ms:1000}"
    )
    public void poll() {

        /*
         * Never process marketplace orders before the
         * initial Inventory snapshot has entered the
         * ordered stock outbox.
         */
        if (!startupState.isReady()) {
            return;
        }

        feedProcessor.pollOnce();
    }
}