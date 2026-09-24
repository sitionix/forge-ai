package com.sitionix.forgeagent.infrastructure.local.mcp;

import com.sitionix.forgeagent.domain.model.McpEncryptedCredential;
import com.sitionix.forgeagent.domain.port.McpCredentialCipher;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM with context-bound AAD and versioned key selection. */
public final class AesGcmMcpCredentialCipher implements McpCredentialCipher {
    private final McpLocalKeySource source;
    private final SecureRandom random = new SecureRandom();
    public AesGcmMcpCredentialCipher(McpLocalKeySource source) { this.source = source; }
    public McpEncryptedCredential encrypt(UUID installation, UUID connection, String purpose, byte[] plaintext) {
        if (plaintext == null || plaintext.length == 0) throw new IllegalArgumentException("Invalid credential");
        try {
            McpLocalKeys keys = source.keys();
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(keys.key(keys.activeId()),"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(aad(installation,connection,purpose));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new McpEncryptedCredential(keys.activeId(),ByteBuffer.allocate(nonce.length+ciphertext.length).put(nonce).put(ciphertext).array());
        } catch (Exception ex) { throw new IllegalArgumentException("Credential encryption unavailable"); }
    }
    public byte[] decrypt(UUID installation, UUID connection, String purpose, McpEncryptedCredential encrypted) {
        try {
            byte[] key = source.keys().key(encrypted.keyId());
            byte[] packed = encrypted.bytes();
            if (key == null || packed.length < 29) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,packed,0,12));
            cipher.updateAAD(aad(installation,connection,purpose));
            return cipher.doFinal(packed,12,packed.length-12);
        } catch (Exception ex) { throw new IllegalArgumentException("Credential decryption unavailable"); }
    }
    private static byte[] aad(UUID installation, UUID connection, String purpose) {
        if (installation == null || connection == null || purpose == null || purpose.isBlank()) throw new IllegalArgumentException("Invalid credential context");
        return ("forge-mcp-v1:"+installation+":"+connection+":"+purpose).getBytes(StandardCharsets.UTF_8);
    }
}
