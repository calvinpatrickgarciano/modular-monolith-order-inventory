package edu.cit.garciano.channel;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class TianggeHeartbeatScheduler {

    private final ChannelGateway channelGateway;

    TianggeHeartbeatScheduler(
            ChannelGateway channelGateway
    ) {
        this.channelGateway =
                channelGateway;
    }

    /*
     * The initial heartbeat is performed by
     * TianggeStartupPublisher.
     *
     * After that, continue every 30 seconds.
     */
    @Scheduled(
            fixedDelayString =
                    "${channel.tiangge.heartbeat-ms:30000}",
            initialDelayString =
                    "${channel.tiangge.heartbeat-ms:30000}"
    )
    public void heartbeat() {

        channelGateway.heartbeat();
    }
}