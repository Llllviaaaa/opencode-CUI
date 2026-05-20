package com.opencode.cui.skill.telemetry.crypto;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * WeLink 上报加解密工具：RSA-OAEP-SHA256 包 AES-128-GCM。
 *
 * <p>对称密钥每次随机生成（128bit），用 RSA 公钥加密后作为 {@code key}；
 * 业务明文用 AES-GCM 加密，IV(12B) || ciphertext+tag 一起 base64 后加 {@code security:} 前缀作为 {@code content}。
 *
 * <p>所有方法对加密失败均通过 catch 抛 {@link CipherException}（业务侧 catch 后 WARN + 跳过）。
 */
@Slf4j
public final class WelinkCipherUtil {

    /** AES 密钥长度（bit） */
    private static final int AES_KEY_BITS = 128;
    /** GCM IV 长度（byte） */
    private static final int GCM_IV_LENGTH = 12;
    /** GCM tag 长度（bit） */
    private static final int GCM_TAG_LENGTH = 128;
    /** content 字段前缀（按外部协议固定） */
    public static final String SECURITY_PREFIX = "security:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private WelinkCipherUtil() {
        // util class
    }

    /**
     * 用 base64 RSA 公钥加密明文 → 信封 {@code {key, content}}。
     *
     * @param publicKeyBase64 base64 编码的 RSA 公钥（X.509 SubjectPublicKeyInfo）
     * @param plaintext       业务明文 JSON
     * @return Envelope
     * @throws CipherException 任何加密阶段失败（密钥解析 / RSA / AES）
     */
    public static Envelope encrypt(String publicKeyBase64, String plaintext) {
        if (publicKeyBase64 == null || publicKeyBase64.isBlank()) {
            throw new CipherException("publicKey is blank");
        }
        if (plaintext == null) {
            throw new CipherException("plaintext is null");
        }
        try {
            // 1) 生成随机 AES-128 key
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(AES_KEY_BITS, RANDOM);
            SecretKey aesKey = kg.generateKey();

            // 2) AES-GCM 加密 plaintext，输出 IV || ciphertext+tag
            byte[] iv = new byte[GCM_IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
            aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ct = aesCipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] ivAndCt = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, ivAndCt, 0, iv.length);
            System.arraycopy(ct, 0, ivAndCt, iv.length, ct.length);
            String content = SECURITY_PREFIX + Base64.getEncoder().encodeToString(ivAndCt);

            // 3) RSA-OAEP-SHA256 加密 AES key
            PublicKey rsaKey = loadRsaPublicKey(publicKeyBase64);
            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            OAEPParameterSpec oaep = new OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
            rsaCipher.init(Cipher.ENCRYPT_MODE, rsaKey, oaep);
            byte[] encKey = rsaCipher.doFinal(aesKey.getEncoded());
            String key = Base64.getEncoder().encodeToString(encKey);

            return new Envelope(key, content);
        } catch (CipherException e) {
            throw e;
        } catch (Exception e) {
            // 不打印 plaintext / publicKey / 密钥；只打类型与消息
            throw new CipherException("WelinkCipherUtil.encrypt failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), e);
        }
    }

    private static PublicKey loadRsaPublicKey(String publicKeyBase64) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    /** RSA + AES 加密后的信封：{@code {key, content}}。 */
    public record Envelope(String key, String content) {
    }

    /** 加密链路异常 — 业务侧 catch 后 WARN + 跳过。 */
    public static class CipherException extends RuntimeException {
        public CipherException(String message) {
            super(message);
        }

        public CipherException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
