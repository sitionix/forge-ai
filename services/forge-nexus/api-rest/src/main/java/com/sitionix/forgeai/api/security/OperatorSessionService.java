package com.sitionix.forgeai.api.security;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded in-memory operator sessions; process restart invalidates all sessions. */
public final class OperatorSessionService {
    private final ProtectedCredentialFile bootstrap;
    private final Clock clock;
    private final Duration ttl;
    private final int capacity;
    private final SecureRandom random=new SecureRandom();
    private final Map<String,Session> sessions=new ConcurrentHashMap<>();
    public OperatorSessionService(ProtectedCredentialFile bootstrap,Clock clock,Duration ttl,int capacity) {
        if (bootstrap==null || clock==null || ttl==null || ttl.compareTo(Duration.ofMinutes(1))<0
                || ttl.compareTo(Duration.ofHours(12))>0 || capacity<1 || capacity>10000)
            throw new IllegalArgumentException("Invalid operator session configuration");
        this.bootstrap=bootstrap; this.clock=clock; this.ttl=ttl; this.capacity=capacity;
    }
    public synchronized Session login(String bootstrapSecret) {
        if (!bootstrap.matches(bootstrapSecret)) throw new IllegalArgumentException("Unauthorized");
        sessions.values().removeIf(s -> !clock.instant().isBefore(s.expiresAt()));
        if (sessions.size()>=capacity) throw new IllegalStateException("Operator sessions unavailable");
        Session session;
        do { session=new Session(randomToken(),randomToken(),clock.instant().plus(ttl)); }
        while (sessions.putIfAbsent(session.id(),session)!=null);
        return session;
    }
    public Optional<Session> find(String id) {
        if (id==null || id.length()>128) return Optional.empty();
        Session session=sessions.get(id);
        if (session==null) return Optional.empty();
        if (!clock.instant().isBefore(session.expiresAt())) { sessions.remove(id,session); return Optional.empty(); }
        return Optional.of(session);
    }
    public void logout(String id) { if (id!=null) sessions.remove(id); }
    public Duration ttl() { return ttl; }
    public boolean csrfMatches(Session session,String candidate) {
        if (session==null || candidate==null || candidate.length()>128) return false;
        return MessageDigest.isEqual(session.csrf().getBytes(StandardCharsets.US_ASCII),candidate.getBytes(StandardCharsets.US_ASCII));
    }
    private String randomToken() {
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static final class Session {
        private final String id,csrf;
        private final Instant expiresAt;
        private Session(String id,String csrf,Instant expiresAt) { this.id=id; this.csrf=csrf; this.expiresAt=expiresAt; }
        public String id() { return id; }
        public String csrf() { return csrf; }
        public Instant expiresAt() { return expiresAt; }
        @Override public String toString() { return "OperatorSession[redacted]"; }
    }
}
