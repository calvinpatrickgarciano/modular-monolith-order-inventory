package edu.cit.garciano.shop;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class OrderConflictException extends RuntimeException {

    public OrderConflictException(String message) {
        super(message);
    }
}