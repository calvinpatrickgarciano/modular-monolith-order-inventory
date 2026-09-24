package edu.cit.garciano.supplier;

record LegacyProductMapping(
        String productId,
        String supplierSku,
        int packSize
) {

    static LegacyProductMapping find(String productId) {

        return switch (productId) {

            case "P100" ->
                    new LegacyProductMapping(
                            "P100",
                            "MHY-8821",
                            6
                    );

            case "P200" ->
                    new LegacyProductMapping(
                            "P200",
                            "MHY-1706",
                            24
                    );

            case "P300" ->
                    new LegacyProductMapping(
                            "P300",
                            "MHY-6162",
                            6
                    );

            default -> null;
        };
    }
}