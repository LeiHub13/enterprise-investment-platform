package com.enterprise.investment.business;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContactEncryptionTest {
    private final byte[] keyBytes = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private final ContactEncryption encryption = new ContactEncryption(Base64.getEncoder().encodeToString(keyBytes));

    @Test
    void roundTripUsesDifferentNoncesAndAuthenticatesContext() {
        byte[] first = encryption.encrypt("13900001111", 10, 20, "phone");
        byte[] second = encryption.encrypt("13900001111", 10, 20, "phone");

        assertEquals("13900001111", encryption.decrypt(first, 10, 20, "phone"));
        assertFalse(Arrays.equals(first, second));
        assertThrows(ResponseStatusException.class, () -> encryption.decrypt(first, 10, 21, "phone"));
        assertThrows(ResponseStatusException.class, () -> encryption.decrypt(first, 11, 20, "phone"));
        assertThrows(ResponseStatusException.class, () -> encryption.decrypt(first, 10, 20, "email"));
        first[first.length - 1] ^= 1;
        assertThrows(ResponseStatusException.class, () -> encryption.decrypt(first, 10, 20, "phone"));
    }

    @Test
    void missingOrInvalidKeyDoesNotStoreOrAlterSensitiveData() {
        var unconfigured = new ContactEncryption("");
        assertNull(unconfigured.encrypt(null, 10, 20, "phone"));
        assertNull(unconfigured.decrypt(null, 10, 20, "phone"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ResponseStatusException.class,
                        () -> unconfigured.encrypt("13900001111", 10, 20, "phone")).getStatusCode());
        assertThrows(IllegalStateException.class, () -> new ContactEncryption("invalid"));

        byte[] original = encryption.encrypt("sample@example.invalid", 10, 20, "email");
        byte[] snapshot = original.clone();
        var otherKey = new ContactEncryption(Base64.getEncoder().encodeToString(new byte[32]));
        assertThrows(ResponseStatusException.class, () -> otherKey.decrypt(original, 10, 20, "email"));
        assertArrayEquals(snapshot, original);
    }
}
