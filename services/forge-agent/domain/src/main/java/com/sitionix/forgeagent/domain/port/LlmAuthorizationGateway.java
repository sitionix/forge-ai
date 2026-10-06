package com.sitionix.forgeagent.domain.port;

import java.util.List;

/** Outgoing managed provider protocol. No browser ownership, application IDs or tokens. */
public interface LlmAuthorizationGateway {
    Session openSession();

    interface Session extends AutoCloseable {
        Account readAccount(boolean refreshToken);
        Login startLogin();
        void cancelLogin(String providerLoginId);
        void logout();
        List<Event> drainEvents();
        boolean healthy();
        @Override void close();
    }

    record Account(boolean connected, String email, String plan) { }
    record Login(String providerLoginId, String authUrl) {
        @Override public String toString() { return "Login[redacted]"; }
    }
    enum EventType { LOGIN_COMPLETED, ACCOUNT_UPDATED, FAILED }
    record Event(EventType type, String providerLoginId, boolean success) {
        public static Event completed(String id, boolean success) { return new Event(EventType.LOGIN_COMPLETED, id, success); }
        public static Event updated() { return new Event(EventType.ACCOUNT_UPDATED, null, false); }
        public static Event failed() { return new Event(EventType.FAILED, null, false); }
        @Override public String toString() { return "Event[type=" + type + ", success=" + success + "]"; }
    }
}
