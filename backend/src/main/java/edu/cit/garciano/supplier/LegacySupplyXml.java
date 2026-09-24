package edu.cit.garciano.supplier;

import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

final class LegacySupplyXml {

    private LegacySupplyXml() {
    }

    static String authRequest(
            String clientId,
            String apiKey
    ) {
        return """
                <AuthRequest>
                    <ClientId>%s</ClientId>
                    <ApiKey>%s</ApiKey>
                </AuthRequest>
                """.formatted(
                escape(clientId),
                escape(apiKey)
        );
    }

    static String purchaseOrderRequest(
            String supplierSku,
            int quantity,
            String buyerRef
    ) {
        return """
                <PurchaseOrder>
                    <SupplierSku>%s</SupplierSku>
                    <Qty>%d</Qty>
                    <BuyerRef>%s</BuyerRef>
                </PurchaseOrder>
                """.formatted(
                escape(supplierSku),
                quantity,
                escape(buyerRef)
        );
    }

    static String sessionToken(String xml) {
        return value(xml, "SessionToken");
    }

    static LegacyPurchaseOrderAck purchaseOrderAck(
            String xml
    ) {
        return new LegacyPurchaseOrderAck(
                value(xml, "PoNumber"),
                Integer.parseInt(
                        value(xml, "StatusCode")
                )
        );
    }

    static LegacyPurchaseOrderStatus purchaseOrderStatus(
            String xml
    ) {
        return new LegacyPurchaseOrderStatus(
                Integer.parseInt(
                        value(xml, "StatusCode")
                )
        );
    }

    static String errorCode(String xml) {
        try {
            return value(xml, "Code");
        } catch (Exception exception) {
            return "UNKNOWN";
        }
    }

    private static String value(
            String xml,
            String tagName
    ) {
        try {
            DocumentBuilderFactory factory =
                    DocumentBuilderFactory.newInstance();

            // Helps prevent unsafe XML entity processing.
            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl",
                    true
            );

            factory.setExpandEntityReferences(false);

            Document document =
                    factory.newDocumentBuilder().parse(
                            new ByteArrayInputStream(
                                    xml.getBytes(
                                            StandardCharsets.UTF_8
                                    )
                            )
                    );

            var nodes =
                    document.getElementsByTagName(
                            tagName
                    );

            if (nodes.getLength() == 0) {
                throw new IllegalStateException(
                        "Missing XML element: "
                                + tagName
                );
            }

            return nodes
                    .item(0)
                    .getTextContent()
                    .trim();

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to parse LegacySupply XML",
                    exception
            );
        }
    }

    private static String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}