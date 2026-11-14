package com.mordor.kelly.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CryptoServiceTest {

    @Test
    void encryptThenDecryptReturnsPlaintext() {
        CryptoService crypto = new CryptoService();
        crypto.initialize("secret", "aB3dE5gH");

        String plain = "你好，Kelly";
        String cipher = crypto.encrypt(plain);

        assertNotEquals(plain, cipher);
        assertEquals(plain, crypto.decrypt(cipher));
    }

    @Test
    void differentPaddingProducesDifferentKey() {
        CryptoService a = new CryptoService();
        a.initialize("secret", "padding-one");
        String cipher = a.encrypt("hello");

        CryptoService b = new CryptoService();
        b.initialize("secret", "padding-two");

        assertThrows(CryptoService.CryptoException.class, () -> b.decrypt(cipher));
    }

    @Test
    void samePasswordAndPaddingCanCrossDecrypt() {
        CryptoService sender = new CryptoService();
        sender.initialize("pass", "pad==");
        CryptoService receiver = new CryptoService();
        receiver.initialize("pass", "pad==");

        assertEquals("ping", receiver.decrypt(sender.encrypt("ping")));
    }

    @Test
    void rejectsShortCiphertext() {
        CryptoService crypto = new CryptoService();
        crypto.initialize("secret", "pad");
        assertThrows(CryptoService.CryptoException.class, () -> crypto.decrypt("AAAA"));
    }

    @Test
    void archiveKeyIgnoresSessionPadding() {
        CryptoService archive = CryptoService.forArchive("secret", "OFFICE");
        String cipher = archive.encrypt("kelly-history-v1");

        CryptoService sessionA = new CryptoService();
        sessionA.initialize("secret", "padding-one");
        CryptoService sessionB = new CryptoService();
        sessionB.initialize("secret", "padding-two");

        assertThrows(CryptoService.CryptoException.class, () -> sessionA.decrypt(cipher));
        assertThrows(CryptoService.CryptoException.class, () -> sessionB.decrypt(cipher));
        assertEquals("kelly-history-v1", CryptoService.forArchive("secret", "OFFICE").decrypt(cipher));
    }

    @Test
    void archiveKeyDependsOnPasswordAndImCode() {
        String cipher = CryptoService.forArchive("secret", "ROOM-A").encrypt("hello");

        assertThrows(CryptoService.CryptoException.class,
                () -> CryptoService.forArchive("wrong", "ROOM-A").decrypt(cipher));
        assertThrows(CryptoService.CryptoException.class,
                () -> CryptoService.forArchive("secret", "ROOM-B").decrypt(cipher));
    }
}
