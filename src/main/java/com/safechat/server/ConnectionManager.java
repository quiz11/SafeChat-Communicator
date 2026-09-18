package com.safechat.server;

import com.safechat.shared.MessageDTO; ///// DODAC

import java.util.HashMap;
import java.util.Map;

/**
 * Central connection and routing manager for the SafeChat server.
 * <p>
 * Maintains the registry of connected clients and their RSA public keys.
 * Handles thread-safe message routing including public broadcasts and
 * point-to-point private messages.
 */
public class ConnectionManager {

    /**
     * Map storing active client handlers indexed by their unique nickname.
     */
    private final Map<String, ClientHandler> activeClients = new HashMap<>();

    /**
     * Map storing RSA public keys of connected clients indexed by their nickname.
     */
    private final Map<String, byte[]> publicKeys = new HashMap<>();

    /**
     * Registers a new client if the requested nickname is not already in use.
     *
     * @param nick    the desired unique nickname
     * @param handler the handler managing the client's connection
     * @return {@code true} if registration succeeded; {@code false} if the nickname
     *         is taken
     */
    public synchronized boolean registerClient(String nick, ClientHandler handler) {
        if (activeClients.containsKey(nick)) {
            return false;
        }
        activeClients.put(nick, handler);
        return true;
    }

    /**
     * Stores the RSA public key associated with a given client nickname.
     *
     * @param nick      the client's nickname
     * @param publicKey the X.509-encoded RSA public key bytes
     */
    public synchronized void storePublicKey(String nick, byte[] publicKey) {
        if (publicKey != null) {
            publicKeys.put(nick, publicKey);
        }
    }

    /**
     * Dispatches the public keys of all currently connected clients to a newly
     * joined client.
     *
     * @param newNick    the nickname of the newly joined client
     * @param newHandler the handler of the newly joined client
     */
    public synchronized void sendExistingUsers(String newNick, ClientHandler newHandler) {
        for (Map.Entry<String, byte[]> entry : publicKeys.entrySet()) {
            if (!entry.getKey().equals(newNick)) {
                MessageDTO joinMsg = new MessageDTO(
                        MessageDTO.MessageType.JOIN, entry.getKey(), "ALL",
                        "", entry.getValue());
                newHandler.sendMessage(joinMsg);
            }
        }
    }

    /**
     * Removes a client and their stored public key from the active registries.
     *
     * @param nick the nickname of the client disconnecting
     */
    public synchronized void removeClient(String nick) {
        if (nick != null) {
            activeClients.remove(nick);
            publicKeys.remove(nick);
            System.out.println("[SERVER] Client disconnected, number of active clients: " + activeClients.size());
        }
    }

    /**
     * Checks whether a client with the specified nickname is currently online.
     *
     * @param nick the nickname to check
     * @return {@code true} if the client is currently active; {@code false}
     *         otherwise
     */
    public synchronized boolean isClientActive(String nick) {
        return activeClients.containsKey(nick);
    }

    /**
     * Broadcasts a message to all currently connected clients.
     *
     * @param message the message to broadcast
     */
    public synchronized void broadcast(MessageDTO message) {
        for (ClientHandler client : activeClients.values()) {
            client.sendMessage(message);
        }
    }

    /**
     * Routes a private message directly to its intended recipient.
     * <p>
     * Also forwards a copy back to the sender if applicable, or returns a system
     * error message if the recipient cannot be found.
     *
     * @param message the private message to route
     */
    public synchronized void sendPrivateMessage(MessageDTO message) {
        String recipientNick = message.getRecipient();
        ClientHandler recipientHandler = activeClients.get(recipientNick);
        ClientHandler senderHandler = activeClients.get(message.getSender());

        if (recipientHandler != null) {

            recipientHandler.sendMessage(message);

            if (senderHandler != null && !recipientNick.equals(message.getSender())) {
                senderHandler.sendMessage(message);
            }
        } else {
            if (senderHandler != null) {
                MessageDTO errorMsg = new MessageDTO(MessageDTO.MessageType.CHAT, "Server", message.getSender(),
                        "User " + recipientNick + " is not available");
                senderHandler.sendMessage(errorMsg);
            }
        }
    }
}
