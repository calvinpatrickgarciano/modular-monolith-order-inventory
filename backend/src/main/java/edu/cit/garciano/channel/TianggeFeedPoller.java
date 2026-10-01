package edu.cit.garciano.channel;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class TianggeFeedPoller {

    private final TianggeFeedProcessor feedProcessor;

    TianggeFeedPoller(
            TianggeFeedProcessor feedProcessor
    ) {

        this.feedProcessor =
                feedProcessor;
    }

    /*
     * Tiangge requires decisions within 60 seconds.
     *
     * Polling every 3 seconds gives us plenty of time.
     */
    @Scheduled(
            fixedDelayString =
                    "${channel.tiangge.feed-poll-ms:3000}",
            initialDelayString =
                    "${channel.tiangge.feed-initial-delay-ms:5000}"
    )
    public void poll() {

        feedProcessor.pollOnce();
    }
}