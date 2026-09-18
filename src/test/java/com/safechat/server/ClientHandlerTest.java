package com.safechat.server;

import com.safechat.shared.MessageDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test suite verifying socket transmission behavior in {@link ClientHandler}.
 */
// testy jednostkowe dla klasy ClientHandler
// testujemy metode sendMessage() - czy poprawnie serializuje obiekty do strumienia
class ClientHandlerTest {

    @Test
    @DisplayName("sendMessage() zapisuje zserializowany MessageDTO do strumienia wyjsciowego")
    void testSendMessageWritesToStream() throws Exception {
        ServerSocket serverSocket = new ServerSocket(0); // losowy port
        int port = serverSocket.getLocalPort();

        Socket clientSide = new Socket("localhost", port);
        Socket serverSide = serverSocket.accept();

        try {
            ConnectionManager cm = new ConnectionManager();
            ClientHandler handler = new ClientHandler(serverSide, cm);
            ObjectOutputStream out = new ObjectOutputStream(serverSide.getOutputStream());
            out.flush();

            java.lang.reflect.Field outField = ClientHandler.class.getDeclaredField("out");
            outField.setAccessible(true);
            outField.set(handler, out);

            ObjectInputStream clientIn = new ObjectInputStream(clientSide.getInputStream());

            MessageDTO testMsg = new MessageDTO(
                    MessageDTO.MessageType.CHAT, "Server", "Alice", "Witaj!");
            handler.sendMessage(testMsg);

            MessageDTO received = (MessageDTO) clientIn.readObject();

            assertNotNull(received, "Odebrana wiadomosc nie powinna byc null");
            assertEquals("Server", received.getSender());
            assertEquals("Alice", received.getRecipient());
            assertEquals("Witaj!", received.getContent());
            assertEquals(MessageDTO.MessageType.CHAT, received.getType());
        } finally {
            clientSide.close();
            serverSide.close();
            serverSocket.close();
        }
    }
}
