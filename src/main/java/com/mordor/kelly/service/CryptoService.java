package com.mordor.kelly.service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM。密钥按 design.md：MD5(password + padding) 的 32 位 hex 字符串作为 32 字节密钥。
 * 密文：Base64(IV(12) + ciphertext + tag(16))。
 */
public final class CryptoService {

    public static final class CryptoException extends RuntimeException {
        public CryptoException(String message, Throwable cause) {
            super(message, cause);
        }

        public CryptoException(String message) {
            super(message);
        }
    }

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_LEN = 16;

    private final SecureRandom random = new SecureRandom();
    private byte[] key;

    public static CryptoService forArchive(String password, String imCode) {
        CryptoService crypto = new CryptoService();
        crypto.initialize(password, "|archive|" + imCode);
        return crypto;
    }

    public void initialize(String password, String padding) {
        this.key = deriveKey(password, padding);
    }

    public boolean isReady() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        ensureReady();
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherAndTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] packed = new byte[IV_LEN + cipherAndTag.length];
            System.arraycopy(iv, 0, packed, 0, IV_LEN);
            System.arraycopy(cipherAndTag, 0, packed, IV_LEN, cipherAndTag.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Encryption failed", e);
        }
    }

    public String decrypt(String ciphertext) {
        ensureReady();
        try {
            byte[] packed = Base64.getDecoder().decode(ciphertext);
            if (packed.length < IV_LEN + TAG_LEN) {
                throw new CryptoException("Invalid ciphertext length");
            }
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(packed, 0, iv, 0, IV_LEN);
            byte[] cipherAndTag = new byte[packed.length - IV_LEN];
            System.arraycopy(packed, IV_LEN, cipherAndTag, 0, cipherAndTag.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherAndTag), StandardCharsets.UTF_8);
        } catch (CryptoException e) {
            throw e;
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new CryptoException("Decryption failed", e);
        }
    }

    static byte[] deriveKey(String password, String padding) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest((password + padding).getBytes(StandardCharsets.UTF_8));
            String hex = toHex(digest);
            return hex.getBytes(StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Key derivation failed", e);
        }
    }

    private void ensureReady() {
        if (key == null) {
            throw new CryptoException("CryptoService not initialized");
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
