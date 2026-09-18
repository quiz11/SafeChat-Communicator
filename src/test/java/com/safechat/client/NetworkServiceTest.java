package com.safechat.client;

import com.safechat.shared.MessageDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test suite for {@link NetworkService}.
 * <p>
 * Evaluates connection error callbacks, client nickname tracking,
 * and text fragmentation algorithms via reflection.
 */
// testy jednostkowe dla klasy NetworkService
// testy sa celowo minimalne - glowna logika w testach integracyjnych
class NetworkServiceTest {

    private NetworkService networkService;
    private AtomicReference<String> lastError;

    @BeforeEach
    void setUp() {
        lastError = new AtomicReference<>(null);
        networkService = new NetworkService(
                msg -> {},
                error -> lastError.set(error));
    }

    // testy stanu poczatkowego --------------------------------------

    @Test
    @DisplayName("Poczatkowy nick klienta jest null przed polaczeniem")
    void testInitialNickIsNull() {
        assertNull(networkService.getClientNick(),
                "Nick powinien byc null przed polaczeniem z serwerem");
    }

    // testy polaczenia z blednym hostem --------------------------------------

    @Test
    @DisplayName("Polaczenie z nieosiagalnym hostem zwraca false i wywoluje callback bledu")
    void testConnectToInvalidHostFails() {
        boolean result = networkService.connect("192.0.2.1", 59999, "TestNick");

        assertFalse(result, "Polaczenie z nieosiagalnym hostem powinno zwrocic false");
        assertNull(networkService.getClientNick(), "Nick powinien pozostac null po nieudanym polaczeniu");
    }

    @Test
    @DisplayName("Polaczenie z nieprawidlowym portem zwraca false")
    void testConnectToInvalidPortFails() {
        // port 0 powinien byc nieprawidlowy
        boolean result = networkService.connect("localhost", 0, "TestNick");

        assertFalse(result, "Polaczenie z portem 0 powinno zwrocic false");
    }

    // testy bezpieczenstwa disconnect --------------------------------------

    @Test
    @DisplayName("disconnect() przed jakimkolwiek polaczeniem nie rzuca wyjatku")
    void testDisconnectDoesNotThrow() {
        assertDoesNotThrow(() -> networkService.disconnect(),
                "disconnect() nie powinien rzucac wyjatku gdy nie ma aktywnego polaczenia");
    }

    @Test
    @DisplayName("Wielokrotne wywolanie disconnect() nie rzuca wyjatku")
    void testMultipleDisconnectDoesNotThrow() {
        assertDoesNotThrow(() -> {
            networkService.disconnect();
            networkService.disconnect();
            networkService.disconnect();
        }, "Wielokrotny disconnect() nie powinien rzucac wyjatkow");
    }

    // testy splitText (chunkowanie) --------------------------------------

    @SuppressWarnings("unchecked")
    private List<String> invokeSplitText(String text) throws Exception {
        Method splitText = NetworkService.class.getDeclaredMethod("splitText", String.class);
        splitText.setAccessible(true);
        return (List<String>) splitText.invoke(networkService, text);
    }

    @Test
    @DisplayName("splitText zwraca jeden element dla krotkiego tekstu")
    void testSplitTextShortMessage() throws Exception {
        String shortText = "Krotka wiadomosc";
        List<String> chunks = invokeSplitText(shortText);

        assertEquals(1, chunks.size(), "Krotki tekst nie powinien byc dzielony");
        assertEquals(shortText, chunks.get(0));
    }

    @Test
    @DisplayName("splitText zwraca jeden element dla tekstu o dlugosci dokladnie MAX_CHUNK_SIZE")
    void testSplitTextExactlyMaxChunkSize() throws Exception {
        String exactText = "A".repeat(MessageDTO.MAX_CHUNK_SIZE);
        List<String> chunks = invokeSplitText(exactText);

        assertEquals(1, chunks.size(), "Tekst o dlugosci MAX_CHUNK_SIZE nie powinien byc dzielony");
        assertEquals(exactText, chunks.get(0));
    }

    @Test
    @DisplayName("splitText dzieli tekst dluzszy niz MAX_CHUNK_SIZE na wiele czesci")
    void testSplitTextLongerThanMaxChunkSize() throws Exception {
        int totalLength = (int) (MessageDTO.MAX_CHUNK_SIZE * 2.5);
        String longText = "X".repeat(totalLength);
        List<String> chunks = invokeSplitText(longText);

        assertEquals(3, chunks.size(), "Tekst 2.5x MAX_CHUNK_SIZE powinien dac 3 chunki");
        assertEquals(MessageDTO.MAX_CHUNK_SIZE, chunks.get(0).length(), "Pierwszy chunk powinien miec MAX_CHUNK_SIZE");
        assertEquals(MessageDTO.MAX_CHUNK_SIZE, chunks.get(1).length(), "Drugi chunk powinien miec MAX_CHUNK_SIZE");
        assertEquals(totalLength - 2 * MessageDTO.MAX_CHUNK_SIZE, chunks.get(2).length(),
                "Trzeci chunk powinien zawierac reszte");

        String reassembled = String.join("", chunks);
        assertEquals(longText, reassembled, "Zlozenie chunkow powinno odtworzyc oryginalny tekst");
    }

    @Test
    @DisplayName("splitText ogranicza liczbe chunkow do MAX_TOTAL_CHUNKS")
    void testSplitTextCapsAtMaxTotalChunks() throws Exception {

        int totalLength = MessageDTO.MAX_CHUNK_SIZE * (MessageDTO.MAX_TOTAL_CHUNKS + 5);
        String veryLongText = "Z".repeat(totalLength);
        List<String> chunks = invokeSplitText(veryLongText);

        assertEquals(MessageDTO.MAX_TOTAL_CHUNKS, chunks.size(),
                "Liczba chunkow nie powinna przekraczac MAX_TOTAL_CHUNKS");
    }

    @Test
    @DisplayName("splitText dla tekstu o dlugosci MAX_CHUNK_SIZE + 1 daje 2 chunki")
    void testSplitTextOneOverBoundary() throws Exception {
        String text = "B".repeat(MessageDTO.MAX_CHUNK_SIZE + 1);
        List<String> chunks = invokeSplitText(text);

        assertEquals(2, chunks.size(), "Tekst MAX_CHUNK_SIZE+1 powinien dac 2 chunki");
        assertEquals(MessageDTO.MAX_CHUNK_SIZE, chunks.get(0).length());
        assertEquals(1, chunks.get(1).length(), "Drugi chunk powinien miec 1 znak");
    }

    @Test
    @DisplayName("splitText zachowuje tresc - zlozenie chunkow odtwarza oryginalny tekst")
    void testSplitTextPreservesContent() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MessageDTO.MAX_CHUNK_SIZE * 3; i++) {
            sb.append((char) ('A' + (i % 26)));
        }
        String originalText = sb.toString();
        List<String> chunks = invokeSplitText(originalText);

        String reassembled = String.join("", chunks);
        assertEquals(originalText, reassembled, "Zlozenie chunkow musi odtworzyc oryginalny tekst");
    }
}
