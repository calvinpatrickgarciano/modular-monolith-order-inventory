package edu.cit.garciano.channel;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import edu.cit.garciano.runtime.AppInstanceIdentity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
class TianggeClient implements ChannelGateway {

    private static final Logger log =
            LoggerFactory.getLogger(
                    TianggeClient.class
            );

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AppInstanceIdentity appInstanceIdentity;

    private final String baseUrl;
    private final String clientId;
    private final String apiKey;
    private final String appName;

    TianggeClient(
            ObjectMapper objectMapper,
            AppInstanceIdentity appInstanceIdentity,

            @Value("${channel.tiangge.base-url}")
            String baseUrl,

            @Value("${channel.tiangge.client-id}")
            String clientId,

            @Value("${channel.tiangge.api-key}")
            String apiKey,

            @Value("${channel.tiangge.app-name:garciano-shop}")
            String appName
    ) {

        this.objectMapper =
                objectMapper;

        this.appInstanceIdentity =
                appInstanceIdentity;

        this.baseUrl =
                baseUrl;

        this.clientId =
                clientId;

        this.apiKey =
                apiKey;

        this.appName =
                appName;

        this.httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(
                                Duration.ofSeconds(3)
                        )
                        .build();
    }

    // =========================================================
    // TASK 1 - HEARTBEAT
    // =========================================================

    @Override
    public void heartbeat() {

        String body =
                """
                {
                  "appName": "%s",
                  "startedAt": "%s",
                  "uptimeSeconds": %d
                }
                """.formatted(
                        appName,
                        appInstanceIdentity.startedAt(),
                        appInstanceIdentity.uptimeSeconds()
                );

        HttpRequest request =
                baseRequest(
                        baseUrl
                                + "/instances/heartbeat"
                )
                        .POST(
                                HttpRequest.BodyPublishers
                                        .ofString(body)
                        )
                        .build();

        HttpResponse<String> response =
                sendWithRetry(
                        request,
                        "heartbeat"
                );

        if (isSuccessful(response)) {

            log.info(
                    "Tiangge heartbeat successful. Instance={}",
                    appInstanceIdentity.instanceId()
            );
        }
    }

    // =========================================================
    // TASK 2 - LISTINGS
    // =========================================================

    @Override
    public void publishListings() {

        String body =
                """
                [
                  {
                    "sellerSku": "P100",
                    "title": "Wireless Mouse",
                    "supplierSku": "MHY-8821"
                  },
                  {
                    "sellerSku": "P200",
                    "title": "Mechanical Keyboard",
                    "supplierSku": "MHY-1706"
                  },
                  {
                    "sellerSku": "P300",
                    "title": "USB-C Hub",
                    "supplierSku": "MHY-6162"
                  }
                ]
                """;

        HttpRequest request =
                baseRequest(
                        baseUrl
                                + "/listings"
                )
                        .PUT(
                                HttpRequest.BodyPublishers
                                        .ofString(body)
                        )
                        .build();

        HttpResponse<String> response =
                sendWithRetry(
                        request,
                        "publish listings"
                );

        if (isSuccessful(response)) {

            log.info(
                    "Tiangge listings published successfully"
            );
        }
    }

    // =========================================================
    // TASK 3 - STOCK
    // =========================================================

    @Override
    public boolean publishStock(
            String sellerSku,
            int available
    ) {

        if (available < 0) {

            throw new IllegalArgumentException(
                    "Available stock cannot be negative"
            );
        }

        String body =
                """
                [
                  {
                    "sellerSku": "%s",
                    "available": %d
                  }
                ]
                """.formatted(
                        sellerSku,
                        available
                );

        HttpRequest request =
                baseRequest(
                        baseUrl
                                + "/stock"
                )
                        .PUT(
                                HttpRequest.BodyPublishers
                                        .ofString(body)
                        )
                        .build();

        HttpResponse<String> response =
                sendWithRetry(
                        request,
                        "publish stock for "
                                + sellerSku
                );

        return isSuccessful(response);
    }

    // =========================================================
    // TASK 4 - ORDER FEED
    // =========================================================

    @Override
    public FeedBatch fetchFeed(
            long after
    ) {

        HttpRequest request =
                baseRequest(
                        baseUrl
                                + "/feed?after="
                                + after
                                + "&limit=20"
                )
                        .GET()
                        .build();

        HttpResponse<String> response =
                sendWithRetry(
                        request,
                        "poll order feed"
                );

        if (!isSuccessful(response)) {
            return null;
        }

        try {

            JsonNode root =
                    objectMapper.readTree(
                            response.body()
                    );

            List<FeedEvent> events =
                    new ArrayList<>();

            for (
                    JsonNode event :
                    root.path("events")
            ) {

                List<Line> lines =
                        new ArrayList<>();

                for (
                        JsonNode line :
                        event.path("lines")
                ) {

                    lines.add(
                            new Line(
                                    line.path(
                                            "sellerSku"
                                    ).asText(),

                                    line.path(
                                            "qty"
                                    ).asInt()
                            )
                    );
                }

                String type =
                        event.path(
                                "type"
                        ).asText();

                String deadline;

                if (
                        "ORDER_PLACED".equals(
                                type
                        )
                ) {

                    deadline =
                            event.path(
                                    "decisionDeadline"
                            ).asText();

                } else {

                    deadline =
                            event.path(
                                    "confirmDeadline"
                            ).asText();
                }

                events.add(
                        new FeedEvent(
                                event.path(
                                        "seq"
                                ).asLong(),

                                event.path(
                                        "eventId"
                                ).asText(),

                                type,

                                event.path(
                                        "orderId"
                                ).asText(),

                                deadline,

                                lines
                        )
                );
            }

            return new FeedBatch(
                    events,
                    root.path(
                            "nextCursor"
                    ).asLong(after)
            );

        } catch (Exception exception) {

            log.error(
                    "Unable to parse Tiangge feed: {}",
                    exception.getMessage()
            );

            return null;
        }
    }

    // =========================================================
    // TASK 4 - DECISION
    // =========================================================

    @Override
    public boolean sendDecision(
            String tianggeOrderId,
            String decision,
            Long shopOrderId
    ) {

        try {

            JsonNode bodyNode =
                    objectMapper.createObjectNode()
                            .put(
                                    "decision",
                                    decision
                            )
                            .put(
                                    "shopOrderId",
                                    "SO-" + shopOrderId
                            );

            String body =
                    objectMapper.writeValueAsString(
                            bodyNode
                    );

            HttpRequest request =
                    baseRequest(
                            baseUrl
                                    + "/orders/"
                                    + tianggeOrderId
                                    + "/decision"
                    )
                            .POST(
                                    HttpRequest.BodyPublishers
                                            .ofString(body)
                            )
                            .build();

            HttpResponse<String> response =
                    sendWithRetry(
                            request,
                            "decision for "
                                    + tianggeOrderId
                    );

            if (isSuccessful(response)) {

                log.info(
                        "Tiangge order {} decided as {}",
                        tianggeOrderId,
                        decision
                );

                return true;
            }

            return false;

        } catch (Exception exception) {

            log.error(
                    "Unable to build Tiangge decision: {}",
                    exception.getMessage()
            );

            return false;
        }
    }

    // =========================================================
    // TASK 5 - CUSTOMER CANCELLATION
    // =========================================================

    @Override
    public boolean confirmCancellation(
            String tianggeOrderId
    ) {

        String body =
                """
                {
                  "restocked": true
                }
                """;

        HttpRequest request =
                baseRequest(
                        baseUrl
                                + "/orders/"
                                + tianggeOrderId
                                + "/cancellation"
                )
                        .POST(
                                HttpRequest.BodyPublishers
                                        .ofString(body)
                        )
                        .build();

        HttpResponse<String> response =
                sendWithRetry(
                        request,
                        "cancellation confirmation for "
                                + tianggeOrderId
                );

        if (isSuccessful(response)) {

            log.info(
                    "Tiangge cancellation confirmed for {}",
                    tianggeOrderId
            );

            return true;
        }

        return false;
    }

    // =========================================================
// TASK 6 - BACKORDER RESOLUTION
// =========================================================

@Override
public boolean sendResolution(
        String tianggeOrderId,
        String status
) {

    if (
            !"ACCEPTED".equals(status)
                    &&
            !"CANCELLED".equals(status)
    ) {

        throw new IllegalArgumentException(
                "Resolution must be ACCEPTED or CANCELLED"
        );
    }

    try {

        String body =
                objectMapper.writeValueAsString(
                        objectMapper
                                .createObjectNode()
                                .put(
                                        "status",
                                        status
                                )
                );

        HttpRequest request =
                baseRequest(
                        baseUrl
                                + "/orders/"
                                + tianggeOrderId
                                + "/resolution"
                )
                        .POST(
                                HttpRequest.BodyPublishers
                                        .ofString(body)
                        )
                        .build();

        HttpResponse<String> response =
                sendWithRetry(
                        request,
                        "backorder resolution for "
                                + tianggeOrderId
                );

        if (isSuccessful(response)) {

            log.info(
                    "Tiangge backorder {} resolved as {}",
                    tianggeOrderId,
                    status
            );

            return true;
        }

        return false;

    } catch (Exception exception) {

        log.error(
                "Unable to send Tiangge backorder resolution: {}",
                exception.getMessage()
        );

        return false;
    }
}

    // =========================================================
    // COMMON REQUEST
    // =========================================================

    private HttpRequest.Builder baseRequest(
            String url
    ) {

        return HttpRequest.newBuilder()
                .uri(
                        URI.create(url)
                )
                .timeout(
                        Duration.ofSeconds(3)
                )
                .header(
                        "Content-Type",
                        "application/json"
                )
                .header(
                        "X-Client-Id",
                        clientId
                )
                .header(
                        "Authorization",
                        "Bearer " + apiKey
                )
                .header(
                        "X-Client-Instance",
                        appInstanceIdentity.instanceId()
                );
    }

    // =========================================================
    // RETRIES
    // =========================================================

    private HttpResponse<String>
    sendWithRetry(
            HttpRequest request,
            String operation
    ) {

        for (
                int attempt = 1;
                attempt <= 3;
                attempt++
        ) {

            try {

                HttpResponse<String> response =
                        httpClient.send(
                                request,
                                HttpResponse.BodyHandlers
                                        .ofString()
                        );

                if (
                        response.statusCode() == 503
                                && attempt < 3
                ) {

                    log.warn(
                            "Tiangge {} unavailable. Attempt {}/3",
                            operation,
                            attempt
                    );

                    backoff(attempt);

                    continue;
                }

                if (
                        response.statusCode() < 200
                                || response.statusCode() >= 300
                ) {

                    log.warn(
                            "Tiangge {} returned HTTP {}: {}",
                            operation,
                            response.statusCode(),
                            response.body()
                    );
                }

                return response;

            } catch (
                    HttpTimeoutException exception
            ) {

                log.warn(
                        "Tiangge {} timed out. Attempt {}/3",
                        operation,
                        attempt
                );

                backoff(attempt);

            } catch (
                    IOException exception
            ) {

                log.warn(
                        "Tiangge {} network error. Attempt {}/3: {}",
                        operation,
                        attempt,
                        exception.getMessage()
                );

                backoff(attempt);

            } catch (
                    InterruptedException exception
            ) {

                Thread.currentThread()
                        .interrupt();

                return null;
            }
        }

        log.error(
                "Tiangge {} failed after 3 attempts",
                operation
        );

        return null;
    }

    private boolean isSuccessful(
            HttpResponse<String> response
    ) {

        return response != null
                && response.statusCode() >= 200
                && response.statusCode() < 300;
    }

    private void backoff(
            int attempt
    ) {

        if (attempt >= 3) {
            return;
        }

        long delay =
                switch (attempt) {

                    case 1 -> 250;

                    case 2 -> 500;

                    default -> 1000;
                };

        try {

            Thread.sleep(delay);

        } catch (
                InterruptedException exception
        ) {

            Thread.currentThread()
                    .interrupt();
        }
    }
}