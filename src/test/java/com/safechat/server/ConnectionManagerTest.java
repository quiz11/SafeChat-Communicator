package com.safechat.server;

import com.safechat.shared.MessageDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit test suite for {@link ConnectionManager}.
 * <p>
 * Evaluates client registration, public key storage, unicast and broadcast routing,
 * and concurrent thread-safety utilizing Mockito mock handlers.
 */
// testy jednostkowe dla klasy ConnectionManager
// uzywamy Mockito do tworzenia mock-ow ClientHandler
// testujemy logike rejestracji, broadcastu, wiadomosci prywatnych i wielowatkowosci
class ConnectionManagerTest {

    private ConnectionManager connectionManager;

    @BeforeEach
    void setUp() {
        connectionManager = new ConnectionManager();
    }

    // testy rejestracji klientow --------------------------------------

    @Test
    @DisplayName("Rejestracja klienta z unikalnym nickiem zwraca true")
    void testRegisterClientSucceeds() {
        ClientHandler mockHandler = mock(ClientHandler.class);

        boolean result = connectionManager.registerClient("Alice", mockHandler);

        assertTrue(result, "Rejestracja z unikalnym nickiem powinna zwrocic true");
    }

    @Test
    @DisplayName("Rejestracja klienta z zajętym nickiem zwraca false")
    void testRegisterDuplicateNickFails() {
        ClientHandler handler1 = mock(ClientHandler.class);
        ClientHandler handler2 = mock(ClientHandler.class);

        connectionManager.registerClient("Alice", handler1);
        boolean result = connectionManager.registerClient("Alice", handler2);

        assertFalse(result, "Rejestracja z zajetym nickiem powinna zwrocic false");
    }

    @Test
    @DisplayName("Po usunieciu klienta, jego nick moze byc ponownie zarejestrowany")
    void testRemoveClientAndReregister() {
        ClientHandler handler1 = mock(ClientHandler.class);
        ClientHandler handler2 = mock(ClientHandler.class);

        connectionManager.registerClient("Alice", handler1);
        connectionManager.removeClient("Alice");

        boolean result = connectionManager.registerClient("Alice", handler2);
        assertTrue(result, "Nick powinien byc dostepny po usunieciu klienta");
    }

    // testy isClientActive --------------------------------------

    @Test
    @DisplayName("isClientActive zwraca true dla zarejestrowanego i false dla niezarejestrowanego")
    void testIsClientActive() {
        ClientHandler mockHandler = mock(ClientHandler.class);
        connectionManager.registerClient("Alice", mockHandler);

        assertTrue(connectionManager.isClientActive("Alice"), "Zarejestrowany klient powinien byc aktywny");
        assertFalse(connectionManager.isClientActive("Bob"), "Niezarejestrowany klient nie powinien byc aktywny");
    }

    @Test
    @DisplayName("Po removeClient klient nie jest juz aktywny")
    void testIsClientActiveAfterRemove() {
        ClientHandler mockHandler = mock(ClientHandler.class);
        connectionManager.registerClient("Alice", mockHandler);
        connectionManager.removeClient("Alice");

        assertFalse(connectionManager.isClientActive("Alice"),
                "Klient powinien byc nieaktywny po usunieciu");
    }

    // testy broadcast --------------------------------------

    @Test
    @DisplayName("broadcast wysyla wiadomosc do wszystkich zarejestrowanych klientow")
    void testBroadcastSendsToAllClients() {
        ClientHandler handler1 = mock(ClientHandler.class);
        ClientHandler handler2 = mock(ClientHandler.class);
        ClientHandler handler3 = mock(ClientHandler.class);

        connectionManager.registerClient("Alice", handler1);
        connectionManager.registerClient("Bob", handler2);
        connectionManager.registerClient("Carol", handler3);

        MessageDTO msg = new MessageDTO(
                MessageDTO.MessageType.CHAT, "Alice", "ALL", "Hej wszystkim!");

        connectionManager.broadcast(msg);

        verify(handler1).sendMessage(msg);
        verify(handler2).sendMessage(msg);
        verify(handler3).sendMessage(msg);
    }

    @Test
    @DisplayName("broadcast do pustej listy klientow nie rzuca wyjatku")
    void testBroadcastToEmptyListDoesNotThrow() {
        MessageDTO msg = new MessageDTO(
                MessageDTO.MessageType.CHAT, "Alice", "ALL", "Hej!");

        assertDoesNotThrow(() -> connectionManager.broadcast(msg),
                "Broadcast do pustej listy nie powinien rzucac wyjatku");
    }

    // testy wiadomosci prywatnych --------------------------------------

    @Test
    @DisplayName("sendPrivateMessage dostarcza wiadomosc do odbiorcy i nadawcy (echo)")
    void testPrivateMessageDelivery() {
        ClientHandler aliceHandler = mock(ClientHandler.class);
        ClientHandler bobHandler = mock(ClientHandler.class);

        connectionManager.registerClient("Alice", aliceHandler);
        connectionManager.registerClient("Bob", bobHandler);

        MessageDTO msg = new MessageDTO(
                MessageDTO.MessageType.CHAT, "Alice", "Bob", "Hej Bob!");

        connectionManager.sendPrivateMessage(msg);

        verify(bobHandler).sendMessage(msg);
        verify(aliceHandler).sendMessage(msg);
    }

    @Test
    @DisplayName("sendPrivateMessage do offline uzytkownika wysyla blad do nadawcy")
    void testPrivateMessageToOfflineUser() {
        ClientHandler aliceHandler = mock(ClientHandler.class);
        connectionManager.registerClient("Alice", aliceHandler);

        MessageDTO msg = new MessageDTO(
                MessageDTO.MessageType.CHAT, "Alice", "NonExistent", "Hej!");

        connectionManager.sendPrivateMessage(msg);

        verify(aliceHandler).sendMessage(argThat(errorMsg ->
                errorMsg.getType() == MessageDTO.MessageType.CHAT
                        && errorMsg.getSender().equals("Server")
                        && errorMsg.getContent().contains("NonExistent")
        ));
    }

    // testy kluczy publicznych --------------------------------------

    @Test
    @DisplayName("storePublicKey + sendExistingUsers wysyla klucze do nowego klienta")
    void testStoreAndSendPublicKeys() {
        ClientHandler aliceHandler = mock(ClientHandler.class);
        ClientHandler bobHandler = mock(ClientHandler.class);

        connectionManager.registerClient("Alice", aliceHandler);
        byte[] aliceKey = {1, 2, 3};
        connectionManager.storePublicKey("Alice", aliceKey);
        connectionManager.registerClient("Bob", bobHandler);
        connectionManager.sendExistingUsers("Bob", bobHandler);

        verify(bobHandler).sendMessage(argThat(joinMsg ->
                joinMsg.getType() == MessageDTO.MessageType.JOIN
                        && joinMsg.getSender().equals("Alice")
                        && joinMsg.getPublicKey() != null
        ));
    }

    @Test
    @DisplayName("storePublicKey z null nie rzuca wyjatku")
    void testStoreNullPublicKey() {
        assertDoesNotThrow(() -> connectionManager.storePublicKey("Alice", null),
                "Zapisanie null-owego klucza nie powinno rzucac wyjatku");
    }

    // testy wielowatkowe --------------------------------------

    @Test
    @DisplayName("Wielowatkowa rejestracja roznych nickow - wszystkie powinny sie udac")
    void testConcurrentRegistration() throws Exception {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String nick = "User" + i;
            ClientHandler handler = mock(ClientHandler.class);
            results.add(executor.submit(() -> {
                startLatch.await();
                return connectionManager.registerClient(nick, handler);
            }));
        }

        startLatch.countDown();

        int successCount = 0;
        for (Future<Boolean> future : results) {
            if (future.get(5, TimeUnit.SECONDS)) {
                successCount++;
            }
        }

        executor.shutdown();
        assertEquals(threadCount, successCount,
                "Wszystkie rozne nicki powinny byc zarejestrowane pomyslnie");
        }

    @Test
    @DisplayName("Wielowatkowa rejestracja tego samego nicku - dokladnie jeden watek wygrywa")
    void testConcurrentDuplicateRegistration() throws Exception {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            ClientHandler handler = mock(ClientHandler.class);
            results.add(executor.submit(() -> {
                startLatch.await();
                return connectionManager.registerClient("SameNick", handler);
            }));
        }

        startLatch.countDown();

        int successCount = 0;
        for (Future<Boolean> future : results) {
            if (future.get(5, TimeUnit.SECONDS)) {
                successCount++;
            }
        }

        executor.shutdown();
        assertEquals(1, successCount,
                "Dokladnie jeden watek powinien zrejestrowac nick pomyslnie");
    }
}
