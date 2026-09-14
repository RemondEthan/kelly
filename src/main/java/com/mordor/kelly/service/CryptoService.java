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
 * AES-256-GCM 加密服务
 *
 * 本类实现了消息的加密和解密功能，使用 AES-256-GCM 算法
 * GCM 模式提供了认证加密（Authenticated Encryption），可以检测密文是否被篡改
 *
 * 密钥派生过程：
 * 1. 将密码和填充字符串拼接
 * 2. 使用 MD5 哈希算法计算摘要
 * 3. 将摘要转换为 32 字节的十六进制字符串作为密钥
 *
 * 密文格式：
 * Base64(IV(12字节) + 密文 + 认证标签(16字节))
 *
 * 注意：MD5 已被证明不安全，但本项目用于演示目的
 */
public final class CryptoService {

    /**
     * 加密/解密异常
     * 当加密或解密操作失败时抛出
     */
    public static final class CryptoException extends RuntimeException {
        public CryptoException(String message, Throwable cause) {
            super(message, cause);
        }

        public CryptoException(String message) {
            super(message);
        }
    }

    /**
     * 初始化向量（IV）长度
     * AES-GCM 推荐使用 12 字节的 IV
     */
    private static final int IV_LEN = 12;

    /**
     * 认证标签位数
     * AES-GCM 推荐使用 128 位（16字节）的认证标签
     */
    private static final int TAG_BITS = 128;

    /**
     * 认证标签字节长度
     */
    private static final int TAG_LEN = 16;

    /**
     * 安全随机数生成器
     * 用于生成加密所需的随机 IV
     */
    private final SecureRandom random = new SecureRandom();

    /**
     * 加密密钥
     * 通过 initialize() 方法初始化
     */
    private byte[] key;

    /**
     * 创建历史档案专用的加密服务
     * 使用特殊的填充字符串 "|archive|" + imCode
     * 确保历史档案的密钥与聊天密钥不同
     *
     * @param password 密码
     * @param imCode 聊天室标识码
     * @return 配置好的加密服务实例
     */
    public static CryptoService forArchive(String password, String imCode) {
        CryptoService crypto = new CryptoService();
        crypto.initialize(password, "|archive|" + imCode);
        return crypto;
    }

    /**
     * 初始化加密服务
     * 使用密码和填充字符串派生加密密钥
     *
     * @param password 密码
     * @param padding 填充字符串（通常是服务器返回的 padding）
     */
    public void initialize(String password, String padding) {
        this.key = deriveKey(password, padding);
    }

    /**
     * 检查加密服务是否已初始化
     * @return true 表示已初始化，可以进行加密/解密操作
     */
    public boolean isReady() {
        return key != null;
    }

    /**
     * 加密明文消息
     * 加密过程：
     * 1. 生成随机 12 字节 IV
     * 2. 使用 AES/GCM/NoPadding 加密
     * 3. 将 IV + 密文 + 认证标签拼接
     * 4. Base64 编码
     *
     * @param plaintext 明文消息
     * @return Base64 编码的密文
     */
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

    /**
     * 解密密文消息
     * 解密过程：
     * 1. Base64 解码
     * 2. 提取 IV（前12字节）
     * 3. 提取密文和认证标签
     * 4. 使用 AES/GCM/NoPadding 解密
     * 5. 返回明文
     *
     * @param ciphertext Base64 编码的密文
     * @return 解密后的明文
     * @throws CryptoException 解密失败时抛出
     */
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

    /**
     * 派生加密密钥
     * 使用 MD5 哈希算法：
     * 1. 拼接密码和填充字符串
     * 2. 计算 MD5 摘要（16字节）
     * 3. 将摘要转换为 32 字节的十六进制字符串
     *
     * @param password 密码
     * @param padding 填充字符串
     * @return 32 字节的密钥
     */
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

    /**
     * 确保加密服务已初始化
     * @throws CryptoException 未初始化时抛出
     */
    private void ensureReady() {
        if (key == null) {
            throw new CryptoException("CryptoService not initialized");
        }
    }

    /**
     * 字节数组转十六进制字符串
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
