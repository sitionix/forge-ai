package com.sitionix.forgeai.api;

public class LaneNotFoundException extends RuntimeException {

    public LaneNotFoundException(final String message) {
        super(message);
    }
}
