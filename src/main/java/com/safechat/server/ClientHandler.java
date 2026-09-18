package com.safechat.server;

import com.safechat.shared.MessageDTO;

import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

/**
 * Worker thread handler for an individual client connection on the SafeChat server.
 * <p>
 * Responsibilities include:
 * <ul>
 *   <li>Establishing object serialization streams with an {@link ObjectInputFilter} for security.</li>
 *   <li>Enforcing message size constraints and chunk count limits.</li>
 *   <li>Validating client registration requests and enforcing unique, sanitized nicknames.</li>
 *   <li>Relaying messages to {@link ConnectionManager} (broadcast or private unicast).</li>
 *   <li>Notifying other clients via {@code LEAVE} broadcast upon client disconnection.</li>
 * </ul>
 */
public class ClientHandler implements Runnable {
    /** Client's TCP socket connection */
    private final Socket socket;

    /** Reference to the shared connection manager */
    private final ConnectionManager connectionManager;

    /** Deserialization input stream for incoming messages */
    private ObjectInputStream in;

    /** Serialization output stream for outgoing messages. */
    private ObjectOutputStream out;

    /** Validated nickname of the authenticated client */
    private String clientNick;

    /**
     * Constructs a new handler for the specified client socket.
     *
     * @param socket            the connected client's TCP socket
     * @param connectionManager the central connection manager
     */
    public ClientHandler(Socket socket, ConnectionManager connectionManager){
        this.socket = socket;
        this.connectionManager = connectionManager;
    }

    /**
     * Main execution loop for reading and dispatching client messages
     * <p>
     * Initializes object streams, applies deserialization filtering, reads messages, 
     * processes registration or relaying, and cleanly cleans up resources when disconnected.
     */
    @Override 
    public void run(){
        try{
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in = new ObjectInputStream(socket.getInputStream());

            in.setObjectInputFilter(filterInfo -> {
                Class<?> class1 = filterInfo.serialClass();
                if(class1 == null) return ObjectInputFilter.Status.UNDECIDED;
                if(class1.getName().startsWith("com.safechat.shared.")) return ObjectInputFilter.Status.ALLOWED;
                if(class1.isArray() || class1.getName().startsWith("java.lang.")) return ObjectInputFilter.Status.ALLOWED;
                return ObjectInputFilter.Status.REJECTED;
            });

            while (true) {
                MessageDTO message = (MessageDTO) in.readObject();

                if (message.getContent() != null && message.getContent().length() > MessageDTO.MAX_CHUNK_SIZE * 2) {
                    System.err.println("[SERVER] Rejected oversized message from " + clientNick);
                    continue;
                }
                if (message.getTotalChunks() > MessageDTO.MAX_TOTAL_CHUNKS) {
                    System.err.println("[SERVER] Rejected message with too many chunks from " + clientNick);
                    continue;
                }

                System.out.println("Server received: " + message);

                if (message.getType() == MessageDTO.MessageType.JOIN) {
                    String wantedNick = message.getSender();

                    if (wantedNick == null || wantedNick.trim().isEmpty()
                            || wantedNick.equalsIgnoreCase("ALL")
                            || wantedNick.equalsIgnoreCase("Server")
                            || wantedNick.length() > 32
                            || !wantedNick.matches("^[a-zA-Z0-9_]+$")) {
                        MessageDTO errorMsg = new MessageDTO(MessageDTO.MessageType.NICK_ERROR, "Server",
                                wantedNick != null ? wantedNick : "",
                                "Error: Invalid nick. Must be 1-32 alphanumeric characters or underscores, and not 'ALL' or 'Server'.");
                        sendMessage(errorMsg);
                        continue;
                    }

                    boolean isRegistered = connectionManager.registerClient(wantedNick, this);

                    if (!isRegistered) {
                        MessageDTO errorMsg = new MessageDTO(MessageDTO.MessageType.NICK_ERROR, "Server", wantedNick,
                                "Error: Nick '" + wantedNick + "' is already used");
                        sendMessage(errorMsg);
                        continue;
                    }

                    MessageDTO okMsg = new MessageDTO(MessageDTO.MessageType.JOIN_OK, "Server", wantedNick, "OK");
                    sendMessage(okMsg);

                    this.clientNick = wantedNick;

                    connectionManager.storePublicKey(wantedNick, message.getPublicKey());

                    connectionManager.sendExistingUsers(wantedNick, this);

                    connectionManager.broadcast(message);

                } else if (message.getType() == MessageDTO.MessageType.READ_RECEIPT) {
                    connectionManager.sendPrivateMessage(message);
                } else if (message.getType() == MessageDTO.MessageType.KEY_EXCHANGE) {
                    connectionManager.sendPrivateMessage(message);
                } else {
                    if ("ALL".equals(message.getRecipient())) {
                        connectionManager.broadcast(message);
                    } else {
                        connectionManager.sendPrivateMessage(message);
                    }
                }
            }
        } catch (java.io.EOFException e) {
            System.out.println("Client disconnected");
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Error: " + e.getMessage());
        } finally {
            if (this.clientNick != null) {
                MessageDTO leaveMsg = MessageDTO.createLeave(this.clientNick);
                connectionManager.broadcast(leaveMsg);
                connectionManager.removeClient(this.clientNick);
            }
            closeEverything();
        }
    }

    /**
     * Sends a message to the connected client over the object output stream in a
     * thread-safe manner.
     * <p>
     * Resets the stream cache after flushing to prevent memory leaks from object
     * reference retention.
     *
     * @param message the message to transmit
     */
    public synchronized void sendMessage(MessageDTO message) {
        try {
            if (out != null) {
                out.writeObject(message);
                out.flush();
                out.reset();
            }
        } catch (IOException e) {
            System.err.println("Error with sending message: " + e.getMessage());
        }
    }

    /**
     * Gracefully closes network streams and socket connection in reverse order of
     * creation.
     */
    private void closeEverything() {
        try {
            if (out != null)
                out.close();
            if (in != null)
                in.close();
            if (socket != null && !socket.isClosed())
                socket.close();
        } catch (IOException e) {
            // ignore
        }
    }
}