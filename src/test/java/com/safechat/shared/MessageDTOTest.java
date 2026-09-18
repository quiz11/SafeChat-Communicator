package com.safechat.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit test suite for {@link MessageDTO}.
 * <p>
 * Verifies constructors, defensive copying, immutability, the {@link MessageDTO.Builder},
 * factory methods, string serialization representations, and Java Object Serialization round-trips.
 */
// testy jednostkowe dla klasy MessageDTO
// sprawdza konstruktory, gettery, immutability, Builder, metody fabryczne, toString oraz serializacje
class MessageDTOTest {

        // testy konstruktorow --------------------------------------

        @Test
        @DisplayName("Konstruktor bazowy (type, sender, recipient, content) ustawia pola poprawnie")
        void testBasicConstructorFields() {
                MessageDTO msg = new MessageDTO(
                                MessageDTO.MessageType.CHAT, "Alice", "Bob", "Hello!");

                assertEquals(MessageDTO.MessageType.CHAT, msg.getType());
                assertEquals("Alice", msg.getSender());
                assertEquals("Bob", msg.getRecipient());
                assertEquals("Hello!", msg.getContent());
                assertNull(msg.getEncryptedPayload(), "encryptedPayload powinien byc null");
                assertNull(msg.getPublicKey(), "publicKey powinien byc null");
        }

        @Test
        @DisplayName("Konstruktor szyfrowany (type, sender, recipient, byte[]) ustawia encryptedPayload")
        void testEncryptedConstructorFields() {
                byte[] payload = { 1, 2, 3, 4, 5 };
                MessageDTO msg = new MessageDTO(
                                MessageDTO.MessageType.CHAT, "Alice", "Bob", payload);

                assertEquals(MessageDTO.MessageType.CHAT, msg.getType());
                assertEquals("Alice", msg.getSender());
                assertEquals("Bob", msg.getRecipient());
                assertArrayEquals(payload, msg.getEncryptedPayload());
                assertNull(msg.getContent(), "content powinien byc null dla konstruktora szyfrowanego");
        }

        @Test
        @DisplayName("Konstruktor z kluczem publicznym (JOIN) ustawia publicKey")
        void testPublicKeyConstructorFields() {
                byte[] pubKey = { 10, 20, 30 };
                MessageDTO msg = new MessageDTO(
                                MessageDTO.MessageType.JOIN, "Alice", "ALL", "joined", pubKey);

                assertEquals(MessageDTO.MessageType.JOIN, msg.getType());
                assertEquals("Alice", msg.getSender());
                assertEquals("ALL", msg.getRecipient());
                assertEquals("joined", msg.getContent());
                assertArrayEquals(pubKey, msg.getPublicKey());
        }

        // testy timestamp --------------------------------------

        @Test
        @DisplayName("Timestamp jest ustawiony i ma sensowna wartosc (blisko aktualnego czasu)")
        void testTimestampIsSet() {
                long before = System.currentTimeMillis();
                MessageDTO msg = new MessageDTO(
                                MessageDTO.MessageType.CHAT, "Alice", "Bob", "test");
                long after = System.currentTimeMillis();

                assertTrue(msg.getTimestamp() >= before, "Timestamp powinien byc >= czas przed utworzeniem");
                assertTrue(msg.getTimestamp() <= after, "Timestamp powinien byc <= czas po utworzeniu");
        }

        // testy niemutowalnosci i defensywnych kopii --------------------------------------

        @Test
        @DisplayName("Defensywna kopia tablicy chroni przed mutacja stanu po utworzeniu obiektu")
        void testDefensiveCopying() {
                byte[] originalPayload = { 1, 2, 3 };
                MessageDTO msg = new MessageDTO(MessageDTO.MessageType.CHAT, "Alice", "Bob", originalPayload);

                // modyfikacja oryginalnej tablicy
                originalPayload[0] = 99;
                assertEquals(1, msg.getEncryptedPayload()[0], "Encrypted payload w DTO nie powinien sie zmienic");

                // modyfikacja tablicy zwroconej przez getter
                byte[] returned = msg.getEncryptedPayload();
                returned[0] = 77;
                assertEquals(1, msg.getEncryptedPayload()[0], "Kolejny getter powinien zwrocic niezmieniony stan");
        }

        // testy wzorca Builder --------------------------------------

        @Test
        @DisplayName("Builder poprawnie buduje obiekt MessageDTO ze wszystkimi polami")
        void testBuilderSuccess() {
                byte[] pubKey = { 1, 2, 3 };
                MessageDTO msg = new MessageDTO.Builder(MessageDTO.MessageType.JOIN, "Alice", "ALL")
                                .content("joined")
                                .publicKey(pubKey)
                                .chunkInfo("chunk-123", 0, 1)
                                .build();

                assertEquals(MessageDTO.MessageType.JOIN, msg.getType());
                assertEquals("Alice", msg.getSender());
                assertEquals("ALL", msg.getRecipient());
                assertEquals("joined", msg.getContent());
                assertArrayEquals(pubKey, msg.getPublicKey());
                assertEquals("chunk-123", msg.getMessageId());
                assertEquals(0, msg.getChunkIndex());
                assertEquals(1, msg.getTotalChunks());
        }

        @Test
        @DisplayName("Builder rzuca IllegalStateException przy probie ustawienia jednoczesnie content i encryptedPayload")
        void testBuilderRejectsBothContentAndPayload() {
                MessageDTO.Builder builder = new MessageDTO.Builder(MessageDTO.MessageType.CHAT, "Alice", "Bob")
                                .content("Hello");

                assertThrows(IllegalStateException.class, () -> builder.encryptedPayload(new byte[] { 1, 2, 3 }));

                MessageDTO.Builder builder2 = new MessageDTO.Builder(MessageDTO.MessageType.CHAT, "Alice", "Bob")
                                .encryptedPayload(new byte[] { 1, 2, 3 });

                assertThrows(IllegalStateException.class, () -> builder2.content("Hello"));
        }

        // testy metod fabrycznych --------------------------------------

        @Test
        @DisplayName("Statyczne metody fabryczne tworza poprawne MessageDTO")
        void testFactoryMethods() {
                MessageDTO chat = MessageDTO.createChat("Alice", "Bob", "Hi");
                assertEquals(MessageDTO.MessageType.CHAT, chat.getType());
                assertEquals("Hi", chat.getContent());

                byte[] key = { 5, 5, 5 };
                MessageDTO encChat = MessageDTO.createEncryptedChat("Alice", "Bob", key);
                assertEquals(MessageDTO.MessageType.CHAT, encChat.getType());
                assertArrayEquals(key, encChat.getEncryptedPayload());

                MessageDTO join = MessageDTO.createJoin("Alice", key);
                assertEquals(MessageDTO.MessageType.JOIN, join.getType());
                assertEquals("Alice", join.getSender());

                MessageDTO leave = MessageDTO.createLeave("Alice");
                assertEquals(MessageDTO.MessageType.LEAVE, leave.getType());
                assertEquals("Alice", leave.getSender());

                MessageDTO joinOk = MessageDTO.createJoinOk("Alice");
                assertEquals(MessageDTO.MessageType.JOIN_OK, joinOk.getType());

                MessageDTO nickErr = MessageDTO.createNickError("Alice", "Taken");
                assertEquals(MessageDTO.MessageType.NICK_ERROR, nickErr.getType());
                assertEquals("Taken", nickErr.getContent());

                MessageDTO keyEx = MessageDTO.createKeyExchange("Alice", "Bob", key);
                assertEquals(MessageDTO.MessageType.KEY_EXCHANGE, keyEx.getType());

                MessageDTO receipt = MessageDTO.createReadReceipt("Alice", "Bob", key);
                assertEquals(MessageDTO.MessageType.READ_RECEIPT, receipt.getType());
        }

        // testy toString --------------------------------------

        @Test
        @DisplayName("toString dla wiadomosci zaszyfrowanej pokazuje [encrypted, N bytes]")
        void testToStringEncrypted() {
                byte[] payload = new byte[128];
                MessageDTO msg = new MessageDTO(
                                MessageDTO.MessageType.CHAT, "Alice", "Bob", payload);

                String result = msg.toString();
                assertTrue(result.contains("[encrypted, 128 bytes]"),
                                "Powinno zawierac informacje o zaszyfrowanej tresci, dostano: " + result);
                assertTrue(result.contains("Alice"), "Powinno zawierac nadawce");
                assertTrue(result.contains("Bob"), "Powinno zawierac odbiorce");
        }

        @Test
        @DisplayName("toString dla wiadomosci tekstowej pokazuje tresc")
        void testToStringPlaintext() {
                MessageDTO msg = new MessageDTO(
                                MessageDTO.MessageType.CHAT, "Alice", "Bob", "Hello world!");

                String result = msg.toString();
                assertTrue(result.contains("Hello world!"),
                                "Powinno zawierac tresc wiadomosci, dostano: " + result);
        }

        // testy serializacji --------------------------------------

        @Test
        @DisplayName("Serializacja i deserializacja zachowuje wszystkie pola (round-trip)")
        void testSerializationRoundTrip() throws Exception {
                byte[] pubKey = { 1, 2, 3, 4, 5 };
                MessageDTO original = new MessageDTO(
                                MessageDTO.MessageType.JOIN, "Alice", "ALL", "Hello", pubKey);

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(bos);
                oos.writeObject(original);
                oos.flush();
                byte[] serialized = bos.toByteArray();

                ByteArrayInputStream bis = new ByteArrayInputStream(serialized);
                ObjectInputStream ois = new ObjectInputStream(bis);
                MessageDTO restored = (MessageDTO) ois.readObject();

                assertEquals(original.getType(), restored.getType());
                assertEquals(original.getSender(), restored.getSender());
                assertEquals(original.getRecipient(), restored.getRecipient());
                assertEquals(original.getContent(), restored.getContent());
                assertEquals(original.getTimestamp(), restored.getTimestamp());
                assertArrayEquals(original.getPublicKey(), restored.getPublicKey());
                assertNull(restored.getEncryptedPayload());
        }

        // testy enum MessageType --------------------------------------

        @Test
        @DisplayName("Kazdy typ wiadomosci (MessageType) moze byc uzyty w konstruktorze")
        void testAllMessageTypes() {
                for (MessageDTO.MessageType type : MessageDTO.MessageType.values()) {
                        MessageDTO msg = new MessageDTO(type, "sender", "recipient", "content");
                        assertEquals(type, msg.getType(),
                                        "Typ " + type + " powinien byc poprawnie zapisany i odczytany");
                }
        }

        // testy chunkowania --------------------------------------

        @Test
        @DisplayName("Konstruktor bazowy ustawia domyslne wartosci chunkowania (totalChunks=1, chunkIndex=0, messageId=null)")
        void testDefaultChunkFieldsBasicConstructor() {
                MessageDTO msg = new MessageDTO(
                                MessageDTO.MessageType.CHAT, "Alice", "Bob", "Hello!");

                assertEquals(1, msg.getTotalChunks(), "totalChunks domyslnie powinno byc 1");
                assertEquals(0, msg.getChunkIndex(), "chunkIndex domyslnie powinno byc 0");
                assertNull(msg.getMessageId(), "messageId domyslnie powinno byc null");
        }

        @Test
        @DisplayName("Konstruktor szyfrowany ustawia domyslne wartosci chunkowania")
        void testDefaultChunkFieldsEncryptedConstructor() {
                byte[] payload = { 1, 2, 3 };
                MessageDTO msg = new MessageDTO(
                                MessageDTO.MessageType.CHAT, "Alice", "Bob", payload);

                assertEquals(1, msg.getTotalChunks());
                assertEquals(0, msg.getChunkIndex());
                assertNull(msg.getMessageId());
        }

        @Test
        @DisplayName("Konstruktor z kluczem publicznym ustawia domyslne wartosci chunkowania")
        void testDefaultChunkFieldsPublicKeyConstructor() {
                byte[] pubKey = { 10, 20, 30 };
                MessageDTO msg = new MessageDTO(
                                MessageDTO.MessageType.JOIN, "Alice", "ALL", "joined", pubKey);

                assertEquals(1, msg.getTotalChunks());
                assertEquals(0, msg.getChunkIndex());
                assertNull(msg.getMessageId());
        }

        @Test
        @DisplayName("withChunkInfo tworzy nowy MessageDTO z polami messageId, chunkIndex, totalChunks")
        void testWithChunkInfo() {
                MessageDTO original = new MessageDTO(
                                MessageDTO.MessageType.CHAT, "Alice", "Bob", "Chunk 1");

                MessageDTO chunkMsg = original.withChunkInfo("abc-123", 2, 5);

                assertEquals("abc-123", chunkMsg.getMessageId());
                assertEquals(2, chunkMsg.getChunkIndex());
                assertEquals(5, chunkMsg.getTotalChunks());
                assertEquals("Chunk 1", chunkMsg.getContent());
                assertNotSame(original, chunkMsg);
        }

        @Test
        @DisplayName("toString zawiera informacje o chunku gdy totalChunks > 1")
        void testToStringWithChunkInfo() {
                MessageDTO msg = new MessageDTO(
                                MessageDTO.MessageType.CHAT, "Alice", "Bob", "Part of message")
                                .withChunkInfo("test-id", 1, 3);

                String result = msg.toString();
                assertTrue(result.contains("[chunk 2/3]"),
                                "toString powinno zawierac [chunk 2/3], dostano: " + result);
        }

        @Test
        @DisplayName("toString nie zawiera informacji o chunku gdy totalChunks == 1")
        void testToStringWithoutChunkInfoWhenSingleChunk() {
                MessageDTO msg = new MessageDTO(
                                MessageDTO.MessageType.CHAT, "Alice", "Bob", "Normal message");

                String result = msg.toString();
                assertFalse(result.contains("chunk"),
                                "toString nie powinno zawierac 'chunk' dla pojedynczej wiadomosci, dostano: " + result);
        }

        @Test
        @DisplayName("Serializacja zachowuje pola chunkowania (round-trip)")
        void testSerializationRoundTripWithChunkFields() throws Exception {
                MessageDTO original = new MessageDTO(
                                MessageDTO.MessageType.CHAT, "Alice", "Bob", "Chunk content")
                                .withChunkInfo("uuid-test-123", 3, 7);

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(bos);
                oos.writeObject(original);
                oos.flush();

                ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
                ObjectInputStream ois = new ObjectInputStream(bis);
                MessageDTO restored = (MessageDTO) ois.readObject();

                assertEquals(original.getMessageId(), restored.getMessageId());
                assertEquals(original.getChunkIndex(), restored.getChunkIndex());
                assertEquals(original.getTotalChunks(), restored.getTotalChunks());
                assertEquals(original.getContent(), restored.getContent());
                assertEquals(original.getSender(), restored.getSender());
        }

        // testy stalych --------------------------------------

        @Test
        @DisplayName("MAX_CHUNK_SIZE i MAX_TOTAL_CHUNKS maja poprawne wartosci")
        void testChunkConstants() {
                assertEquals(10000, MessageDTO.MAX_CHUNK_SIZE, "MAX_CHUNK_SIZE powinno byc 10000");
                assertEquals(10, MessageDTO.MAX_TOTAL_CHUNKS, "MAX_TOTAL_CHUNKS powinno byc 10");
                assertTrue(MessageDTO.MAX_CHUNK_SIZE > 0, "MAX_CHUNK_SIZE musi byc dodatnie");
                assertTrue(MessageDTO.MAX_TOTAL_CHUNKS > 0, "MAX_TOTAL_CHUNKS musi byc dodatnie");
        }
}
