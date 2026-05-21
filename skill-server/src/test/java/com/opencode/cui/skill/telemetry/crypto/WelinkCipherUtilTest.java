package com.opencode.cui.skill.telemetry.crypto;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RSA-OAEP-SHA256 + AES-128-GCM 信封往返：
 * 用测试 RSA keypair 加密 → 私钥解 AES key → AES-GCM 解 content → 比对明文。
 */
class WelinkCipherUtilTest {

    private static KeyPair keyPair;
    private static String publicKeyBase64;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        keyPair = kpg.generateKeyPair();
        publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    @Test
    @DisplayName("encrypt → 私钥解 AES key → AES-GCM 解 content == plaintext；带 security: 前缀")
    void encryptRoundTrip() throws Exception {
        String plaintext = "{\"hello\":\"world\",\"n\":42}";

        WelinkCipherUtil.Envelope env = WelinkCipherUtil.encrypt(publicKeyBase64, plaintext);

        assertNotNull(env);
        assertNotNull(env.key());
        assertNotNull(env.content());
        assertTrue(env.content().startsWith(WelinkCipherUtil.SECURITY_PREFIX),
                "content must start with security: prefix");

        // 1) 用私钥解 AES key
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        OAEPParameterSpec oaep = new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
        rsa.init(Cipher.DECRYPT_MODE, (PrivateKey) keyPair.getPrivate(), oaep);
        byte[] aesKeyBytes = rsa.doFinal(Base64.getDecoder().decode(env.key()));
        assertEquals(16, aesKeyBytes.length, "AES-128 key must be 16 bytes");

        // 2) 拆 IV(12) || ciphertext+tag → AES-GCM 解
        String ivAndCtBase64 = env.content().substring(WelinkCipherUtil.SECURITY_PREFIX.length());
        byte[] ivAndCt = Base64.getDecoder().decode(ivAndCtBase64);
        byte[] iv = new byte[12];
        byte[] ct = new byte[ivAndCt.length - 12];
        System.arraycopy(ivAndCt, 0, iv, 0, 12);
        System.arraycopy(ivAndCt, 12, ct, 0, ct.length);

        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKeyBytes, "AES"),
                new GCMParameterSpec(128, iv));
        byte[] decrypted = aes.doFinal(ct);

        assertEquals(plaintext, new String(decrypted, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("每次 encrypt 生成的 IV / 密文不同（AES key + IV 都随机）")
    void encryptRandomness() {
        WelinkCipherUtil.Envelope e1 = WelinkCipherUtil.encrypt(publicKeyBase64, "same-text");
        WelinkCipherUtil.Envelope e2 = WelinkCipherUtil.encrypt(publicKeyBase64, "same-text");
        assertNotEquals(e1.content(), e2.content(), "IV must vary across calls");
        assertNotEquals(e1.key(), e2.key(), "AES key must vary across calls");
    }

    @Test
    @DisplayName("publicKey 为空 → CipherException")
    void blankPublicKeyThrows() {
        assertThrows(WelinkCipherUtil.CipherException.class,
                () -> WelinkCipherUtil.encrypt("", "x"));
        assertThrows(WelinkCipherUtil.CipherException.class,
                () -> WelinkCipherUtil.encrypt(null, "x"));
    }

    @Test
    @DisplayName("非法 base64 publicKey → CipherException")
    void invalidPublicKeyThrows() {
        assertThrows(WelinkCipherUtil.CipherException.class,
                () -> WelinkCipherUtil.encrypt("!!!not-a-key!!!", "x"));
    }
}
