package edu.cit.garciano.supplier;

class SupplierUnavailableException
        extends RuntimeException {

    SupplierUnavailableException(String message) {
        super(message);
    }
}