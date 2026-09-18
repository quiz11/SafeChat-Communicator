package com.safechat.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.crypto.SecretKey;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test suite for {@link CryptoService}.
 * <p>
 * Validates RSA key pair generation, X.509 encoding, AES key generation,
 * RSA-OAEP session key encryption/decryption, and AES-256 GCM authenticated encryption.
 */
// testy jednostkowe dla klasy CryptoService
// sprawdza generowanie kluczy, magazyn, szyfrowanie AES-GCM oraz AES przez RSA
class CryptoServiceTest {

    private CryptoService cryptoService;

    @BeforeEach
    void setUp() {
        cryptoService = new CryptoService();
    }

    // testy generowania kluczy RSA --------------------------------------

    @Test
    @DisplayName("Konstruktor generuje pare kluczy RSA - klucz publiczny jest niepusty")
    void testRsaKeyPairGenerated() {
        byte[] publicKeyBytes = cryptoService.getPublicKeyBytes();
        assertNotNull(publicKeyBytes, "Klucz publiczny nie powinien byc null");
        assertTrue(publicKeyBytes.length > 0, "Klucz publiczny powinien miec > 0 bajtow");
    }

    @Test
    @DisplayName("Kazda instancja CryptoService generuje inny klucz publiczny RSA")
    void testDifferentInstancesDifferentKeys() {
        CryptoService other = new CryptoService();
        assertFalse(
                java.util.Arrays.equals(cryptoService.getPublicKeyBytes(), other.getPublicKeyBytes()),
                "Dwie instancje powinny miec rozne klucze publiczne RSA");
    }

    // testy magazynu kluczy publicznych RSA --------------------------------------

    @Test
    @DisplayName("storePublicKey -> hasPublicKey -> getPublicKey round-trip")
    void testPublicKeyStoreAndRetrieve() {
        CryptoService alice = new CryptoService();

        // zapisujemy klucz publiczny Alice w magazynie
        cryptoService.storePublicKey("Alice", alice.getPublicKeyBytes());

        assertTrue(cryptoService.hasPublicKey("Alice"), "Powinien miec klucz Alice");
        assertNotNull(cryptoService.getPublicKey("Alice"), "getPublicKey nie powinien zwrocic null");
    }

    @Test
    @DisplayName("getPublicKey dla nieznanego uzytkownika zwraca null")
    void testPublicKeyNotFoundReturnsNull() {
        assertFalse(cryptoService.hasPublicKey("unknown"), "Nie powinien miec klucza unknown");
        assertNull(cryptoService.getPublicKey("unknown"), "getPublicKey(unknown) powinien zwrocic null");
    }

    // testy magazynu kluczy AES --------------------------------------

    @Test
    @DisplayName("generateAesKey -> storeAesKey -> hasAesKey -> getAesKey round-trip")
    void testAesKeyGenerateStoreRetrieve() {
        SecretKey aesKey = cryptoService.generateAesKey();
        assertNotNull(aesKey, "Wygenerowany klucz AES nie powinien byc null");

        cryptoService.storeAesKey("Bob", aesKey);

        assertTrue(cryptoService.hasAesKey("Bob"), "Powinien miec klucz AES dla Bob");
        assertEquals(aesKey, cryptoService.getAesKey("Bob"), "Odczytany klucz powinien byc identyczny");
    }

    @Test
    @DisplayName("Klucze AES roznych uzytkownikow sa izolowane")
    void testMultipleUsersKeyIsolation() {
        SecretKey keyBob = cryptoService.generateAesKey();
        SecretKey keyCarol = cryptoService.generateAesKey();

        cryptoService.storeAesKey("Bob", keyBob);
        cryptoService.storeAesKey("Carol", keyCarol);

        assertEquals(keyBob, cryptoService.getAesKey("Bob"));
        assertEquals(keyCarol, cryptoService.getAesKey("Carol"));
        assertNotEquals(cryptoService.getAesKey("Bob"), cryptoService.getAesKey("Carol"),
                "Klucze roznych uzytkownikow powinny byc rozne");
    }

    // testy szyfrowania AES-GCM --------------------------------------

    @Test
    @DisplayName("encryptMessage -> decryptMessage zwraca oryginalny tekst")
    void testAesEncryptDecryptRoundTrip() {
        SecretKey aesKey = cryptoService.generateAesKey();
        String plainText = "To jest tajemnicza tajna TopSecret wiadomosc";

        byte[] encrypted = cryptoService.encryptMessage(plainText, aesKey);
        assertNotNull(encrypted, "Zaszyfrowane dane nie powinny byc null");
        assertTrue(encrypted.length > 0, "Zaszyfrowane dane powinny miec > 0 bajtow");

        String decrypted = cryptoService.decryptMessage(encrypted, aesKey);
        assertEquals(plainText, decrypted, "Odszyfrowana wiadomosc powinna byc identyczna z oryginalem");
    }

    @Test
    @DisplayName("Szyfrowanie i deszyfrowanie tekstu z polskimi znakami i emoji")
    void testAesEncryptDecryptUnicode() {
        SecretKey aesKey = cryptoService.generateAesKey();
        String plainText = "Cześć! Jak się masz? 🔒🔑 Zażółć gęślą jaźń, boże jaki cringe";

        byte[] encrypted = cryptoService.encryptMessage(plainText, aesKey);
        String decrypted = cryptoService.decryptMessage(encrypted, aesKey);

        assertEquals(plainText, decrypted, "Unicode powinien byc zachowany po encrypt/decrypt");
    }

    @Test
    @DisplayName("Szyfrowanie i deszyfrowanie pustego stringa")
    void testAesEncryptDecryptEmptyString() {
        SecretKey aesKey = cryptoService.generateAesKey();
        String plainText = "";

        byte[] encrypted = cryptoService.encryptMessage(plainText, aesKey);
        String decrypted = cryptoService.decryptMessage(encrypted, aesKey);

        assertEquals(plainText, decrypted, "Pusty string powinien byc zachowany");
    }

    @Test
    @DisplayName("Szyfrowanie i deszyfrowanie dlugiej wiadomosci (10 000 znakow)")
    void testAesEncryptDecryptLongMessage() {
        SecretKey aesKey = cryptoService.generateAesKey();
        String plainText = "A".repeat(10_000);

        byte[] encrypted = cryptoService.encryptMessage(plainText, aesKey);
        String decrypted = cryptoService.decryptMessage(encrypted, aesKey);

        assertEquals(plainText, decrypted, "Dluga wiadomosc powinna byc zachowana (quite big buddy)");
    }

    @Test
    @DisplayName("Ten sam tekst szyfrowany dwukrotnie daje rozne ciphertexty (losowy IV)")
    void testAesEncryptProducesDifferentCiphertexts() {
        SecretKey aesKey = cryptoService.generateAesKey();
        String plainText = "Identyczna wiadomosc";

        byte[] encrypted1 = cryptoService.encryptMessage(plainText, aesKey);
        byte[] encrypted2 = cryptoService.encryptMessage(plainText, aesKey);

        assertFalse(java.util.Arrays.equals(encrypted1, encrypted2),
                "Dwa szyfrowania tego samego tekstu powinny dac rozne wyniki (rozne IV)");

        // oba powinny sie odsyfrowac do tego samego tekstu
        assertEquals(plainText, cryptoService.decryptMessage(encrypted1, aesKey));
        assertEquals(plainText, cryptoService.decryptMessage(encrypted2, aesKey));
    }

    @Test
    @DisplayName("Deszyfrowanie z nieprawidlowym kluczem AES rzuca wyjatek")
    void testDecryptWithWrongKeyFails() {
        SecretKey correctKey = cryptoService.generateAesKey();
        SecretKey wrongKey = cryptoService.generateAesKey();
        String plainText = "No mowilem juz ze to tajna wiadomosc";

        byte[] encrypted = cryptoService.encryptMessage(plainText, correctKey);

        assertThrows(RuntimeException.class, () ->
                        cryptoService.decryptMessage(encrypted, wrongKey),
                "Deszyfrowanie z nieprawidlowym kluczem powinno rzucic wyjatek");
    }

    // testy szyfrowania klucza AES przez RSA --------------------------------------

    @Test
    @DisplayName("encryptAesKey -> decryptAesKey zwraca ten sam klucz")
    void testRsaEncryptDecryptAesKey() {
        CryptoService alice = new CryptoService();
        CryptoService bob = new CryptoService();

        // Alice zapisuje klucz publiczny Boba
        alice.storePublicKey("Bob", bob.getPublicKeyBytes());

        // Alice generuje klucz AES i szyfruje go kluczem publicznym Boba
        SecretKey originalAesKey = alice.generateAesKey();
        byte[] encryptedAesKey = alice.encryptAesKey(originalAesKey, "Bob");

        assertNotNull(encryptedAesKey, "Zaszyfrowany klucz AES nie powinien byc null");

        // Bob odszyfrowuje klucz AES swoim kluczem prywatnym
        SecretKey restoredAesKey = bob.decryptAesKey(encryptedAesKey);

        assertArrayEquals(originalAesKey.getEncoded(), restoredAesKey.getEncoded(),
                "Odszyfrowany klucz AES powinien byc identyczny z oryginalem");
    }

    @Test
    @DisplayName("encryptAesKey bez zapisanego klucza publicznego rzuca IllegalStateException")
    void testEncryptAesKeyNoPublicKeyThrows() {
        SecretKey aesKey = cryptoService.generateAesKey();

        assertThrows(IllegalStateException.class, () ->
                        cryptoService.encryptAesKey(aesKey, "nonexistent"),
                "Powinien rzucic wyjatek gdy brak klucza publicznego odbiorcy");
    }
}
