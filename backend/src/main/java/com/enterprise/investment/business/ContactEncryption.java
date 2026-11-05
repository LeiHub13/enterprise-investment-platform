package com.enterprise.investment.business;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class ContactEncryption {
    private static final byte FORMAT_VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    ContactEncryption(@Value("${APP_CONTACT_ENCRYPTION_KEY:}") String configuredKey) {
        if (configuredKey.isBlank()) {
            key = null;
            return;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(configuredKey);
            if (bytes.length != 32) {
                throw new IllegalArgumentException("The contact encryption key must contain 32 bytes");
            }
            key = new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("APP_CONTACT_ENCRYPTION_KEY must be a base64-encoded 32-byte key", error);
        }
    }

    byte[] encrypt(String value, long enterpriseId, long contactId, String field) {
        if (value == null) {
            return null;
        }
        requireKey();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(associatedData(enterpriseId, contactId, field));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + nonce.length + encrypted.length)
                    .put(FORMAT_VERSION).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("无法加密联系人信息", error);
        }
    }

    String decrypt(byte[] encrypted, long enterpriseId, long contactId, String field) {
        if (encrypted == null) {
            return null;
        }
        requireKey();
        if (encrypted.length < 1 + NONCE_BYTES + 16 || encrypted[0] != FORMAT_VERSION) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "联系人信息格式无法识别");
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(encrypted);
            buffer.get();
            byte[] nonce = new byte[NONCE_BYTES];
            buffer.get(nonce);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(associatedData(enterpriseId, contactId, field));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "无法解密联系人信息，请检查加密密钥", error);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "请先配置 APP_CONTACT_ENCRYPTION_KEY，再使用联系人电话和邮箱");
        }
    }

    private static byte[] associatedData(long enterpriseId, long contactId, String field) {
        return ("contact:" + enterpriseId + ":" + contactId + ":" + field).getBytes(StandardCharsets.UTF_8);
    }
}
