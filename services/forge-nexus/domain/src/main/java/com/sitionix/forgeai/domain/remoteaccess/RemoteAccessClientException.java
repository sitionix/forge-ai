package com.sitionix.forgeai.domain.remoteaccess;
public final class RemoteAccessClientException extends RuntimeException {
    private final int status;
    private final String code;
    private final String correlationId;
    public RemoteAccessClientException(int status,String code,String message,String correlationId) {
        super(message);this.status=status;this.code=code;this.correlationId=correlationId;
    }
    public int status() { return status; }
    public String code() { return code; }
    public String correlationId() { return correlationId; }
}
