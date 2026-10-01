package edu.cit.garciano.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
public final class AppInstanceIdentity {

    private static final Logger log =
            LoggerFactory.getLogger(AppInstanceIdentity.class);

    private final UUID instanceId;
    private final Instant startedAt;

    public AppInstanceIdentity() {
        this.instanceId = UUID.randomUUID();
        this.startedAt = Instant.now();

        log.info(
                "=================================================="
        );
        log.info(
                "APP INSTANCE ID: {}",
                instanceId
        );
        log.info(
                "APP STARTED AT: {}",
                startedAt
        );
        log.info(
                "=================================================="
        );
    }

    public String instanceId() {
        return instanceId.toString();
    }

    public Instant startedAt() {
        return startedAt;
    }

    public long uptimeSeconds() {
        return Duration.between(
                startedAt,
                Instant.now()
        ).toSeconds();
    }
}