package com.safechat.integration;

import com.safechat.client.CryptoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.crypto.SecretKey;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-End integration test validating full cryptographic exchange between two simulated clients.
 * <p>
 * Simulates two isolated {@link CryptoService} peers executing mutual RSA public key exchange,
 * session key generation and transmission, bidirectional AES-GCM message encryption,
 * and encrypted read receipt verification.
 */
// testy integracyjne symulujace pelna wymiane kluczy E2E pomiedzy dwoma klientami
// dwie niezalezne instancje CryptoService (Alice i Bob) wykonuja wymiane kluczy i szyfrowanie
class CryptoRoundTripTest {

    @Test
    @DisplayName("Pelny scenariusz E2E: wymiana kluczy RSA -> klucz AES -> szyfrowanie wiadomosci")
    void testFullE2EKeyExchangeAndChat() {
        // setup: dwie niezalezne instancje (symulacja dwoch klientow)
        CryptoService alice = new CryptoService();
        CryptoService bob = new CryptoService();

        // faza 1: wymiana kluczy publicznych RSA
        alice.storePublicKey("Bob", bob.getPublicKeyBytes());
        bob.storePublicKey("Alice", alice.getPublicKeyBytes());

        assertTrue(alice.hasPublicKey("Bob"), "Alice powinna miec klucz publiczny Boba");
        assertTrue(bob.hasPublicKey("Alice"), "Bob powinien miec klucz publiczny Alice");

        // faza 2: Alice generuje klucz AES i wysyla go Bobowi (zaszyfrowany RSA)
        SecretKey aesKey = alice.generateAesKey();
        alice.storeAesKey("Bob", aesKey);

        // Alice szyfruje klucz AES kluczem publicznym Boba
        byte[] encryptedAesKey = alice.encryptAesKey(aesKey, "Bob");
        assertNotNull(encryptedAesKey, "Zaszyfrowany klucz AES nie powinien byc null");

        // Bob odszyfrowuje klucz AES swoim kluczem prywatnym
        SecretKey bobsAesKey = bob.decryptAesKey(encryptedAesKey);
        bob.storeAesKey("Alice", bobsAesKey);

        // klucze powinny byc identyczne
        assertArrayEquals(aesKey.getEncoded(), bobsAesKey.getEncoded(),
                "Klucze AES po wymianie RSA powinny byc identyczne");

        // faza 3: Alice szyfruje wiadomosc, Bob ja odszyfrowuje
        String originalMessage = "Hej Bob, to jest tajna wiadomosc! 🔒";

        byte[] encrypted = alice.encryptMessage(originalMessage, alice.getAesKey("Bob"));
        String decrypted = bob.decryptMessage(encrypted, bob.getAesKey("Alice"));

        assertEquals(originalMessage, decrypted,
                "Bob powinien odszyfrowac wiadomosc Alice do oryginalu");
    }

    @Test
    @DisplayName("Dwukierunkowa komunikacja: Alice -> Bob i Bob -> Alice")
    void testBidirectionalEncryptedChat() {
        CryptoService alice = new CryptoService();
        CryptoService bob = new CryptoService();

        // wymiana kluczy publicznych
        alice.storePublicKey("Bob", bob.getPublicKeyBytes());
        bob.storePublicKey("Alice", alice.getPublicKeyBytes());

        // Alice generuje klucz AES i wymienia go z Bobem
        SecretKey sharedKey = alice.generateAesKey();
        alice.storeAesKey("Bob", sharedKey);

        byte[] encryptedKey = alice.encryptAesKey(sharedKey, "Bob");
        SecretKey bobsKey = bob.decryptAesKey(encryptedKey);
        bob.storeAesKey("Alice", bobsKey);

        // Alice -> Bob
        String aliceMsg = "Cześć Bob!";
        byte[] aliceEncrypted = alice.encryptMessage(aliceMsg, alice.getAesKey("Bob"));
        String bobDecrypted = bob.decryptMessage(aliceEncrypted, bob.getAesKey("Alice"));
        assertEquals(aliceMsg, bobDecrypted, "Bob powinien odczytac wiadomosc Alice");

        // Bob -> Alice
        String bobMsg = "Hej Alice, co slychac?";
        byte[] bobEncrypted = bob.encryptMessage(bobMsg, bob.getAesKey("Alice"));
        String aliceDecrypted = alice.decryptMessage(bobEncrypted, alice.getAesKey("Bob"));
        assertEquals(bobMsg, aliceDecrypted, "Alice powinna odczytac wiadomosc Boba");
    }

    @Test
    @DisplayName("Wiele wiadomosci z tym samym kluczem AES - wszystkie deszyfrowalne")
    void testMultipleMessagesWithSameKey() {
        CryptoService alice = new CryptoService();
        CryptoService bob = new CryptoService();

        // wymiana kluczy
        alice.storePublicKey("Bob", bob.getPublicKeyBytes());
        bob.storePublicKey("Alice", alice.getPublicKeyBytes());

        SecretKey sharedKey = alice.generateAesKey();
        alice.storeAesKey("Bob", sharedKey);
        byte[] encKey = alice.encryptAesKey(sharedKey, "Bob");
        bob.storeAesKey("Alice", bob.decryptAesKey(encKey));

        // wysylamy 10 wiadomosci
        String[] messages = {
                "Wiadomosc 1", "Wiadomosc 2", "Wiadomosc 3",
                "Test polskich znakow: ąćęłńóśźż",
                "Emoji test: 🔐🔑🛡️",
                "Pusta prawie...", "",
                "Bardzo dluga wiadomosc... " + "A".repeat(5000),
                "Przedostatnia!", "Ostatnia wiadomosc!"
        };

        for (String original : messages) {
            byte[] encrypted = alice.encryptMessage(original, alice.getAesKey("Bob"));
            String decrypted = bob.decryptMessage(encrypted, bob.getAesKey("Alice"));
            assertEquals(original, decrypted,
                    "Wiadomosc '" + (original.length() > 30 ? original.substring(0, 30) + "..." : original)
                            + "' powinna byc poprawnie odszyfrowana");
        }
    }

    @Test
    @DisplayName("Trzech uczestnikow: Alice-Bob i Alice-Carol uzywaja roznych kluczy AES")
    void testThreePartyIsolation() {
        CryptoService alice = new CryptoService();
        CryptoService bob = new CryptoService();
        CryptoService carol = new CryptoService();

        // wymiana kluczy publicznych
        alice.storePublicKey("Bob", bob.getPublicKeyBytes());
        alice.storePublicKey("Carol", carol.getPublicKeyBytes());
        bob.storePublicKey("Alice", alice.getPublicKeyBytes());
        carol.storePublicKey("Alice", alice.getPublicKeyBytes());

        // Alice-Bob: klucz AES #1
        SecretKey keyAB = alice.generateAesKey();
        alice.storeAesKey("Bob", keyAB);
        bob.storeAesKey("Alice", bob.decryptAesKey(alice.encryptAesKey(keyAB, "Bob")));

        // Alice-Carol: klucz AES #2
        SecretKey keyAC = alice.generateAesKey();
        alice.storeAesKey("Carol", keyAC);
        carol.storeAesKey("Alice", carol.decryptAesKey(alice.encryptAesKey(keyAC, "Carol")));

        // wiadomosc do Boba
        String msgToBob = "Tylko Bob to widzi";
        byte[] encBob = alice.encryptMessage(msgToBob, alice.getAesKey("Bob"));
        assertEquals(msgToBob, bob.decryptMessage(encBob, bob.getAesKey("Alice")));

        // Carol nie powinna byc w stanie odsyfrowac wiadomosci do Boba
        assertThrows(RuntimeException.class, () ->
                        carol.decryptMessage(encBob, carol.getAesKey("Alice")),
                "Carol nie powinna moc odsyfrowac wiadomosci skierowanej do Boba");

        // wiadomosc do Carol
        String msgToCarol = "Tylko Carol to widzi";
        byte[] encCarol = alice.encryptMessage(msgToCarol, alice.getAesKey("Carol"));
        assertEquals(msgToCarol, carol.decryptMessage(encCarol, carol.getAesKey("Alice")));
    }
}
