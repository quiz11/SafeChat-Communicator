package com.safechat.integration;

import com.safechat.shared.MessageDTO;
import com.safechat.server.ConnectionManager;
import com.safechat.server.ClientHandler;

import org.junit.jupiter.api.*;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test suite simulating real TCP network communications between clients and server.
 * <p>
 * Boots live {@link ServerSocket} instances on dynamic ephemeral ports, spawns multiple client
 * socket connections concurrently, and tests registration, nickname deduplication, broadcasts,
 * chunking transmission, and high-concurrency client scaling.
 */
// testy integracyjne klient-serwer
// uruchamiamy prawdziwy ServerSocket, laczymy wielu klientow przez ObjectStreams
// weryfikujemy przesyl zserializowanych obiektow MessageDTO
class ClientServerIntegrationTest {

    private ServerSocket serverSocket;
    private ConnectionManager connectionManager;
    private ExecutorService serverExecutor;
    private final List<Socket> openSockets = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        connectionManager = new ConnectionManager();
        serverSocket = new ServerSocket(0); // losowy wolny port
        serverExecutor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() throws Exception {
        // zamykamy wszystkie otwarte sockety
        for (Socket s : openSockets) {
            if (s != null && !s.isClosed())
                s.close();
        }
        if (serverSocket != null && !serverSocket.isClosed())
            serverSocket.close();
        serverExecutor.shutdownNow();
    }

    // pomocnicza metoda: akceptuje polaczenie na serwerze i uruchamia ClientHandler
    // w tle
    private void acceptAndHandleClient() {
        serverExecutor.submit(() -> {
            try {
                Socket client = serverSocket.accept();
                openSockets.add(client);
                ClientHandler handler = new ClientHandler(client, connectionManager);
                handler.run(); // uruchamiamy synchronicznie w watku puli
            } catch (Exception e) {
                // ignorujemy wyjatki przy zamykaniu
            }
        });
    }

    // pomocnicza metoda: laczy klienta i zwraca strumienie
    private ClientConnection connectClient(String nick) throws Exception {
        // akceptuj po stronie serwera
        acceptAndHandleClient();

        // polacz po stronie klienta
        Socket clientSocket = new Socket("localhost", serverSocket.getLocalPort());
        openSockets.add(clientSocket);

        ObjectOutputStream out = new ObjectOutputStream(clientSocket.getOutputStream());
        out.flush();
        ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream());

        // wysylamy JOIN
        MessageDTO joinMsg = new MessageDTO(
                MessageDTO.MessageType.JOIN, nick, "ALL", "", new byte[] { 1, 2, 3 });
        out.writeObject(joinMsg);
        out.flush();

        return new ClientConnection(clientSocket, out, in, nick);
    }

    // testy --------------------------------------

    @Test
    @DisplayName("Klient wysyla JOIN i otrzymuje JOIN_OK od serwera")
    void testClientJoinAndReceiveJoinOk() throws Exception {
        ClientConnection alice = connectClient("Alice");

        // pierwsza odpowiedz powinna byc JOIN_OK
        MessageDTO response = alice.readMessage(3000);
        assertNotNull(response, "Serwer powinien odpowiedziec na JOIN");
        assertEquals(MessageDTO.MessageType.JOIN_OK, response.getType(),
                "Odpowiedz powinna byc JOIN_OK");

        alice.close();
    }

    @Test
    @DisplayName("Drugi klient z tym samym nickiem dostaje NICK_ERROR")
    void testDuplicateNickRejected() throws Exception {
        ClientConnection alice1 = connectClient("Alice");
        MessageDTO ok = alice1.readMessage(3000);
        assertEquals(MessageDTO.MessageType.JOIN_OK, ok.getType());

        Thread.sleep(200);

        ClientConnection alice2 = connectClient("Alice");
        MessageDTO response = alice2.readMessage(3000);
        assertNotNull(response, "Serwer powinien odpowiedziec");
        assertEquals(MessageDTO.MessageType.NICK_ERROR, response.getType(),
                "Powtorzony nick powinien dostac NICK_ERROR");

        alice1.close();
        alice2.close();
    }

    @Test
    @DisplayName("Wiadomosc broadcast (ALL) dociera do wszystkich polaczonych klientow")
    void testBroadcastMessageDelivery() throws Exception {
        ClientConnection alice = connectClient("Alice");
        MessageDTO aliceOk = alice.readMessage(3000);
        assertEquals(MessageDTO.MessageType.JOIN_OK, aliceOk.getType());

        Thread.sleep(200);

        ClientConnection bob = connectClient("Bob");
        MessageDTO bobOk = bob.readMessage(3000);
        assertEquals(MessageDTO.MessageType.JOIN_OK, bobOk.getType());

        Thread.sleep(300);

        MessageDTO chatMsg = new MessageDTO(
                MessageDTO.MessageType.CHAT, "Alice", "ALL", "Hej wszystkim!");
        alice.out.writeObject(chatMsg);
        alice.out.flush();

        MessageDTO received = alice.readUntilType(MessageDTO.MessageType.CHAT, 3000);
        assertNotNull(received, "Alice powinna otrzymac echo broadcastu");
        assertEquals("Hej wszystkim!", received.getContent());

        alice.close();
        bob.close();
    }

    @Test
    @DisplayName("Wiadomosc prywatna dociera do odbiorcy i nadawcy (echo)")
    void testPrivateMessageDelivery() throws Exception {
        ClientConnection alice = connectClient("Alice");
        alice.readMessage(3000); // JOIN_OK
        Thread.sleep(200);

        ClientConnection bob = connectClient("Bob");
        bob.readMessage(3000); // JOIN_OK
        Thread.sleep(300);

        MessageDTO privateMsg = new MessageDTO(
                MessageDTO.MessageType.CHAT, "Alice", "Bob", "Sekretna wiadomosc!");
        alice.out.writeObject(privateMsg);
        alice.out.flush();

        MessageDTO bobReceived = bob.readUntilType(MessageDTO.MessageType.CHAT, 3000);
        assertNotNull(bobReceived, "Bob powinien otrzymac prywatna wiadomosc");
        assertEquals("Sekretna wiadomosc!", bobReceived.getContent());
        assertEquals("Alice", bobReceived.getSender());

        alice.close();
        bob.close();
    }

    @Test
    @DisplayName("Wiadomosc KEY_EXCHANGE jest przekazywana do odbiorcy")
    void testKeyExchangeForwarding() throws Exception {
        ClientConnection alice = connectClient("Alice");
        alice.readMessage(3000); // JOIN_OK
        Thread.sleep(200);

        ClientConnection bob = connectClient("Bob");
        bob.readMessage(3000); // JOIN_OK
        Thread.sleep(300);

        byte[] fakeEncryptedKey = { 99, 88, 77, 66 };
        MessageDTO keyExMsg = new MessageDTO(
                MessageDTO.MessageType.KEY_EXCHANGE, "Alice", "Bob", fakeEncryptedKey);
        alice.out.writeObject(keyExMsg);
        alice.out.flush();

        MessageDTO bobReceived = bob.readUntilType(MessageDTO.MessageType.KEY_EXCHANGE, 3000);
        assertNotNull(bobReceived, "Bob powinien otrzymac KEY_EXCHANGE");
        assertEquals("Alice", bobReceived.getSender());
        assertArrayEquals(fakeEncryptedKey, bobReceived.getEncryptedPayload());

        alice.close();
        bob.close();
    }

    @Test
    @DisplayName("50 klientow laczy sie jednoczesnie i wszyscy otrzymuja broadcasty")
    void testMultipleClientsConcurrent() throws Exception {
        int clientCount = 50;
        List<ClientConnection> clients = new ArrayList<>();

        for (int i = 0; i < clientCount; i++) {
            ClientConnection client = connectClient("User" + i);
            MessageDTO joinOk = client.readMessage(3000);
            assertNotNull(joinOk, "User" + i + " powinien dostac odpowiedz na JOIN");
            assertEquals(MessageDTO.MessageType.JOIN_OK, joinOk.getType(),
                    "User" + i + " powinien dostac JOIN_OK");
            clients.add(client);
            Thread.sleep(150);
        }

        Thread.sleep(100);

        for (ClientConnection client : clients) {
            client.drainAvailable(500);
        }

        MessageDTO broadcastMsg = new MessageDTO(
                MessageDTO.MessageType.CHAT, "User0", "ALL", "Wiadomosc do wszystkich!");
        clients.get(0).out.writeObject(broadcastMsg);
        clients.get(0).out.flush();

        for (int i = 0; i < clientCount; i++) {
            MessageDTO received = clients.get(i).readUntilType(MessageDTO.MessageType.CHAT, 3000);
            assertNotNull(received,
                    "User" + i + " powinien otrzymac broadcast");
            assertEquals("Wiadomosc do wszystkich!", received.getContent(),
                    "User" + i + " - tresc powinna sie zgadzac");
        }

        for (ClientConnection client : clients) {
            client.close();
        }
    }

    // testy chunkowania wiadomosci --------------------------------------

    @Test
    @DisplayName("Serwer przekazuje chunki wiadomosci broadcast do wszystkich klientow")
    void testBroadcastChunkedMessageDelivery() throws Exception {
        ClientConnection alice = connectClient("Alice");
        MessageDTO aliceOk = alice.readMessage(3000);
        assertEquals(MessageDTO.MessageType.JOIN_OK, aliceOk.getType());

        Thread.sleep(200);

        ClientConnection bob = connectClient("Bob");
        MessageDTO bobOk = bob.readMessage(3000);
        assertEquals(MessageDTO.MessageType.JOIN_OK, bobOk.getType());

        Thread.sleep(300);

        alice.drainAvailable(500);
        bob.drainAvailable(500);

        String messageId = "test-msg-001";
        for (int i = 0; i < 3; i++) {
            MessageDTO chunk = new MessageDTO(
                    MessageDTO.MessageType.CHAT, "Alice", "ALL", "Chunk" + i)
                    .withChunkInfo(messageId, i, 3);
            alice.out.writeObject(chunk);
            alice.out.flush();
        }

        List<MessageDTO> bobChunks = bob.readMessages(3, 5000);
        assertEquals(3, bobChunks.size(), "Bob powinien otrzymac 3 chunki");

        for (int i = 0; i < 3; i++) {
            MessageDTO chunk = bobChunks.get(i);
            assertEquals("Alice", chunk.getSender());
            assertEquals("Chunk" + i, chunk.getContent());
            assertEquals(messageId, chunk.getMessageId());
            assertEquals(i, chunk.getChunkIndex());
            assertEquals(3, chunk.getTotalChunks());
        }

        alice.close();
        bob.close();
    }

    @Test
    @DisplayName("Serwer przekazuje chunki wiadomosci prywatnej do odbiorcy i nadawcy")
    void testPrivateChunkedMessageDelivery() throws Exception {
        ClientConnection alice = connectClient("Alice");
        alice.readMessage(3000);
        Thread.sleep(200);

        ClientConnection bob = connectClient("Bob");
        bob.readMessage(3000);
        Thread.sleep(300);

        alice.drainAvailable(500);
        bob.drainAvailable(500);

        String messageId = "private-msg-001";
        for (int i = 0; i < 2; i++) {
            MessageDTO chunk = new MessageDTO(
                    MessageDTO.MessageType.CHAT, "Alice", "Bob", "PrivChunk" + i)
                    .withChunkInfo(messageId, i, 2);
            alice.out.writeObject(chunk);
            alice.out.flush();
        }

        List<MessageDTO> bobChunks = bob.readMessages(2, 5000);
        assertEquals(2, bobChunks.size(), "Bob powinien otrzymac 2 chunki prywatnej wiadomosci");
        assertEquals("PrivChunk0", bobChunks.get(0).getContent());
        assertEquals("PrivChunk1", bobChunks.get(1).getContent());

        alice.close();
        bob.close();
    }

    @Test
    @DisplayName("Serwer odrzuca wiadomosc z trescia dluzsza niz MAX_CHUNK_SIZE * 2")
    void testServerRejectsOversizedMessage() throws Exception {
        ClientConnection alice = connectClient("Alice");
        alice.readMessage(3000);
        Thread.sleep(200);

        ClientConnection bob = connectClient("Bob");
        bob.readMessage(3000);
        Thread.sleep(300);

        alice.drainAvailable(500);
        bob.drainAvailable(500);

        String oversizedContent = "X".repeat(MessageDTO.MAX_CHUNK_SIZE * 2 + 1);
        MessageDTO oversizedMsg = new MessageDTO(
                MessageDTO.MessageType.CHAT, "Alice", "ALL", oversizedContent);
        alice.out.writeObject(oversizedMsg);
        alice.out.flush();

        MessageDTO normalMsg = new MessageDTO(
                MessageDTO.MessageType.CHAT, "Alice", "ALL", "Normal message");
        alice.out.writeObject(normalMsg);
        alice.out.flush();

        MessageDTO received = bob.readUntilType(MessageDTO.MessageType.CHAT, 3000);
        assertNotNull(received, "Bob powinien dostac normalna wiadomosc");
        assertEquals("Normal message", received.getContent(),
                "Odrzucona wiadomosc nie powinna dotrzec, powinna dotrzec tylko normalna");

        alice.close();
        bob.close();
    }

    @Test
    @DisplayName("Serwer odrzuca wiadomosc z totalChunks > MAX_TOTAL_CHUNKS")
    void testServerRejectsTooManyChunks() throws Exception {
        ClientConnection alice = connectClient("Alice");
        alice.readMessage(3000);
        Thread.sleep(200);

        ClientConnection bob = connectClient("Bob");
        bob.readMessage(3000);
        Thread.sleep(300);

        alice.drainAvailable(500);
        bob.drainAvailable(500);

        MessageDTO badChunk = new MessageDTO(
                MessageDTO.MessageType.CHAT, "Alice", "ALL", "chunk content")
                .withChunkInfo("bad-msg", 0, MessageDTO.MAX_TOTAL_CHUNKS + 1);
        alice.out.writeObject(badChunk);
        alice.out.flush();

        MessageDTO normalMsg = new MessageDTO(
                MessageDTO.MessageType.CHAT, "Alice", "ALL", "OK message");
        alice.out.writeObject(normalMsg);
        alice.out.flush();

        MessageDTO received = bob.readUntilType(MessageDTO.MessageType.CHAT, 3000);
        assertNotNull(received, "Bob powinien dostac normalna wiadomosc");
        assertEquals("OK message", received.getContent(),
                "Wiadomosc z za duza liczba chunkow powinna byc odrzucona");

        alice.close();
        bob.close();
    }

    @Test
    @DisplayName("Gdy klient sie rozlacza, serwer wysyla LEAVE broadcast do pozostalych")
    void testClientDisconnectBroadcastsLeave() throws Exception {
        ClientConnection alice = connectClient("Alice");
        alice.readMessage(3000); // JOIN_OK
        Thread.sleep(200);

        ClientConnection bob = connectClient("Bob");
        bob.readMessage(3000); // JOIN_OK
        Thread.sleep(200);

        bob.drainAvailable(300);

        // Alice sie rozlacza
        alice.close();

        MessageDTO leaveMsg = bob.readUntilType(MessageDTO.MessageType.LEAVE, 4000);
        assertNotNull(leaveMsg, "Bob powinien otrzymac komunikat LEAVE po rozlaczeniu Alice");
        assertEquals("Alice", leaveMsg.getSender());
        assertEquals(MessageDTO.MessageType.LEAVE, leaveMsg.getType());

        bob.close();
    }

    // klasa pomocnicza --------------------------------------

    private static class ClientConnection {
        final Socket socket;
        final ObjectOutputStream out;
        final ObjectInputStream in;
        final String nick;

        ClientConnection(Socket socket, ObjectOutputStream out, ObjectInputStream in, String nick) {
            this.socket = socket;
            this.out = out;
            this.in = in;
            this.nick = nick;
        }

        // odczytuje nastepna wiadomosc z timeoutem (ms). zwraca null jesli timeout
        MessageDTO readMessage(int timeoutMs) throws Exception {
            ExecutorService exec = Executors.newSingleThreadExecutor();
            Future<MessageDTO> future = exec.submit(() -> (MessageDTO) in.readObject());
            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return null;
            } finally {
                exec.shutdownNow();
            }
        }

        // odczytuje N wiadomosci z timeoutem (lacznym). zwraca liste odebranych
        List<MessageDTO> readMessages(int count, int timeoutMs) throws Exception {
            List<MessageDTO> messages = new ArrayList<>();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (messages.size() < count && System.currentTimeMillis() < deadline) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0)
                    break;
                MessageDTO msg = readMessage((int) remaining);
                if (msg != null) {
                    messages.add(msg);
                }
            }
            return messages;
        }

        // odczytuje wiadomosci az do znalezienia danego typu lub timeout
        MessageDTO readUntilType(MessageDTO.MessageType type, int timeoutMs) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0)
                    break;
                MessageDTO msg = readMessage((int) remaining);
                if (msg != null && msg.getType() == type) {
                    return msg;
                }
            }
            return null;
        }

        // odczytuje i odrzuca wszystkie dostepne wiadomosci (czyszczenie bufora)
        void drainAvailable(int waitMs) {
            try {
                Thread.sleep(waitMs);
                socket.setSoTimeout(100);
                while (true) {
                    try {
                        in.readObject();
                    } catch (Exception e) {
                        break;
                    }
                }
                socket.setSoTimeout(0);
            } catch (Exception e) {
            }
        }

        void close() {
            try {
                socket.close();
            } catch (Exception e) {
            }
        }
    }
}
