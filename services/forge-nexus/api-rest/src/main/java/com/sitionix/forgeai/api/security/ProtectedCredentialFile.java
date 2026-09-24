package com.sitionix.forgeai.api.security;

import java.nio.file.Path;
import java.security.*;
import java.util.*;

public final class ProtectedCredentialFile {
    private final byte[] digest;
    public ProtectedCredentialFile(Path path) {
        byte[] raw=ProtectedNexusFile.token(path);
        digest=hash(raw);
        Arrays.fill(raw,(byte)0);
    }
    public boolean matches(String candidate) {
        try {
            if (candidate==null || candidate.length()>512) return false;
            byte[] raw=Base64.getUrlDecoder().decode(candidate);
            boolean canonical=raw.length>=32 && raw.length<=64
                    && Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(candidate);
            byte[] actual=hash(raw); Arrays.fill(raw,(byte)0);
            return canonical && MessageDigest.isEqual(digest,actual);
        } catch (IllegalArgumentException exception) { return false; }
    }
    private static byte[] hash(byte[] raw) {
        try { return MessageDigest.getInstance("SHA-256").digest(raw); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("Protected credential unavailable"); }
    }
    @Override public String toString() { return "ProtectedCredentialFile[redacted]"; }
}
