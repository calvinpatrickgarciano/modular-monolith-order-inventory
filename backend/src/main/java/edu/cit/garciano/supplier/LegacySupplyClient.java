package edu.cit.garciano.supplier;

import edu.cit.garciano.runtime.AppInstanceIdentity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

@Component
class LegacySupplyClient {

    private final HttpClient httpClient;

    private final String baseUrl;
    private final String clientId;
    private final String apiKey;

    /*
     * LAB 4:
     * The same UUID used for Tiangge must also be sent
     * on every LegacySupply request.
     */
    private final AppInstanceIdentity appInstanceIdentity;

    private volatile String sessionToken;

    LegacySupplyClient(
            @Value("${supplier.base-url}") String baseUrl,
            @Value("${supplier.client-id}") String clientId,
            @Value("${supplier.api-key}") String apiKey,
            AppInstanceIdentity appInstanceIdentity
    ) {

        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.apiKey = apiKey;

        this.appInstanceIdentity =
                appInstanceIdentity;

        this.httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(
                                Duration.ofSeconds(3)
                        )
                        .build();
    }

    LegacyPurchaseOrderAck placeOrder(
            String supplierSku,
            int cases,
            String buyerRef,
            String requestId
    ) {

        for (int attempt = 1; attempt <= 3; attempt++) {

            try {

                String token =
                        getSession();

                String body =
                        LegacySupplyXml.purchaseOrderRequest(
                                supplierSku,
                                cases,
                                buyerRef
                        );

                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(
                                        URI.create(
                                                baseUrl
                                                        + "/purchase-orders"
                                        )
                                )
                                .timeout(
                                        Duration.ofSeconds(3)
                                )
                                .header(
                                        "Content-Type",
                                        "application/xml"
                                )
                                .header(
                                        "X-LS-Session",
                                        token
                                )
                                .header(
                                        "X-Request-Id",
                                        requestId
                                )

                                /*
                                 * LAB 4:
                                 * Identify this running copy
                                 * of the Spring Boot application.
                                 */
                                .header(
                                        "X-Client-Instance",
                                        appInstanceIdentity.instanceId()
                                )

                                .POST(
                                        HttpRequest.BodyPublishers
                                                .ofString(body)
                                )
                                .build();

                HttpResponse<String> response =
                        httpClient.send(
                                request,
                                HttpResponse.BodyHandlers.ofString()
                        );

                if (
                        response.statusCode() == 200
                                || response.statusCode() == 201
                ) {

                    return LegacySupplyXml
                            .purchaseOrderAck(
                                    response.body()
                            );
                }

                /*
                 * Session expired.
                 *
                 * Throw away the existing token so
                 * the next attempt signs in again.
                 */
                if (response.statusCode() == 401) {

                    sessionToken = null;

                    backoff(attempt);

                    continue;
                }

                /*
                 * Temporary errors.
                 */
                if (
                        response.statusCode() == 429
                                || response.statusCode() >= 500
                ) {

                    backoff(attempt);

                    continue;
                }

                String errorCode =
                        LegacySupplyXml.errorCode(
                                response.body()
                        );

                throw new IllegalStateException(
                        "LegacySupply rejected purchase order: "
                                + errorCode
                );

            } catch (HttpTimeoutException exception) {

                /*
                 * Retry using the SAME request ID.
                 */
                backoff(attempt);

            } catch (IOException exception) {

                backoff(attempt);

            } catch (InterruptedException exception) {

                Thread.currentThread().interrupt();

                throw new IllegalStateException(
                        "LegacySupply request interrupted",
                        exception
                );
            }
        }

        throw new SupplierUnavailableException(
                "LegacySupply unavailable after 3 attempts"
        );
    }

    LegacyPurchaseOrderStatus getStatus(
            String poNumber
    ) {

        for (int attempt = 1; attempt <= 3; attempt++) {

            try {

                String token =
                        getSession();

                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(
                                        URI.create(
                                                baseUrl
                                                        + "/purchase-orders/"
                                                        + poNumber
                                        )
                                )
                                .timeout(
                                        Duration.ofSeconds(3)
                                )
                                .header(
                                        "X-LS-Session",
                                        token
                                )

                                /*
                                 * LAB 4:
                                 * Same live application instance ID.
                                 */
                                .header(
                                        "X-Client-Instance",
                                        appInstanceIdentity.instanceId()
                                )

                                .GET()
                                .build();

                HttpResponse<String> response =
                        httpClient.send(
                                request,
                                HttpResponse.BodyHandlers.ofString()
                        );

                if (response.statusCode() == 200) {

                    return LegacySupplyXml
                            .purchaseOrderStatus(
                                    response.body()
                            );
                }

                if (response.statusCode() == 401) {

                    sessionToken = null;

                    backoff(attempt);

                    continue;
                }

                if (
                        response.statusCode() == 429
                                || response.statusCode() >= 500
                ) {

                    backoff(attempt);

                    continue;
                }

                String errorCode =
                        LegacySupplyXml.errorCode(
                                response.body()
                        );

                throw new IllegalStateException(
                        "Unable to track LegacySupply order: "
                                + errorCode
                );

            } catch (HttpTimeoutException exception) {

                backoff(attempt);

            } catch (IOException exception) {

                backoff(attempt);

            } catch (InterruptedException exception) {

                Thread.currentThread().interrupt();

                throw new IllegalStateException(
                        "LegacySupply tracking interrupted",
                        exception
                );
            }
        }

        throw new SupplierUnavailableException(
                "LegacySupply unavailable after 3 attempts"
        );
    }

    /*
     * Obtain or reuse the LegacySupply session.
     */
    private synchronized String getSession() {

        if (sessionToken != null) {
            return sessionToken;
        }

        for (int attempt = 1; attempt <= 3; attempt++) {

            try {

                String body =
                        LegacySupplyXml.authRequest(
                                clientId,
                                apiKey
                        );

                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(
                                        URI.create(
                                                baseUrl
                                                        + "/auth/token"
                                        )
                                )
                                .timeout(
                                        Duration.ofSeconds(3)
                                )
                                .header(
                                        "Content-Type",
                                        "application/xml"
                                )

                                /*
                                 * LAB 4:
                                 * Authentication is also a
                                 * LegacySupply request, therefore
                                 * it receives the instance ID.
                                 */
                                .header(
                                        "X-Client-Instance",
                                        appInstanceIdentity.instanceId()
                                )

                                .POST(
                                        HttpRequest.BodyPublishers
                                                .ofString(body)
                                )
                                .build();

                HttpResponse<String> response =
                        httpClient.send(
                                request,
                                HttpResponse.BodyHandlers.ofString()
                        );

                if (response.statusCode() == 200) {

                    sessionToken =
                            LegacySupplyXml.sessionToken(
                                    response.body()
                            );

                    return sessionToken;
                }

                if (
                        response.statusCode() == 429
                                || response.statusCode() >= 500
                ) {

                    backoff(attempt);

                    continue;
                }

                String errorCode =
                        LegacySupplyXml.errorCode(
                                response.body()
                        );

                throw new IllegalStateException(
                        "LegacySupply authentication failed: "
                                + errorCode
                );

            } catch (HttpTimeoutException exception) {

                backoff(attempt);

            } catch (IOException exception) {

                backoff(attempt);

            } catch (InterruptedException exception) {

                Thread.currentThread().interrupt();

                throw new IllegalStateException(
                        "LegacySupply authentication interrupted",
                        exception
                );
            }
        }

        throw new SupplierUnavailableException(
                "Unable to obtain LegacySupply session"
        );
    }

    private void backoff(int attempt) {

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

        } catch (InterruptedException exception) {

            Thread.currentThread().interrupt();
        }
    }
}