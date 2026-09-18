package com.safechat.client;

import com.safechat.shared.MessageDTO;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Networking service managing TCP socket communications for the SafeChat client.
 * <p>
 * Key capabilities:
 * <ul>
 *   <li>Establishes TCP connections to the server and executes the initial registration handshake
 *       (sending nickname and RSA public key).</li>
 *   <li>Secures object deserialization using {@link ObjectInputFilter} to mitigate deserialization attacks.</li>
 *   <li>Runs a background daemon thread continuously listening for incoming {@link MessageDTO} frames.</li>
 *   <li>Integrates with {@link CryptoService} to negotiate AES session keys on demand (via {@code KEY_EXCHANGE}).</li>
 *   <li>Transparently fragments large messages exceeding {@link MessageDTO#MAX_CHUNK_SIZE} and reassembles them upon receipt.</li>
 *   <li>Transmits encrypted read receipts back to message senders.</li>
 * </ul>
 */
public class NetworkService {
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private final CryptoService cryptoService;
    private String clientNick;

    private final Consumer<MessageDTO> onMessageReceived;
    private final Consumer<String> onConnectionError;

    private final Map<String, Map<Integer, String>> chunkBuffer = new ConcurrentHashMap<>();
    private final Map<String, MessageDTO> chunkMetadata = new ConcurrentHashMap<>();
    private final Map<String, Long> chunkTimestamps = new ConcurrentHashMap<>();
    private final Map<String, Integer> chunkExpected = new ConcurrentHashMap<>();

    private static final long CHUNK_TIMEOUT_MS = 30_000;
    private ScheduledExecutorService chunkCleanupScheduler;

    /**
     * Constructs a new NetworkService with user-interface notification callbacks
     *
     * @param onMessageReceived callback invoked on receipt of valid messages or system events
     * @param onConnectionError callback invoked when network failures or disconnections occur
     */
    public NetworkService(Consumer<MessageDTO> onMessageReceived, Consumer<String> onConnectionError) {
        this.onMessageReceived = onMessageReceived;
        this.onConnectionError = onConnectionError;
        this.cryptoService = new CryptoService();
    }

    /**
     * Retrieves the current authenticated nickname of this client.
     *
     * @return the active nickname, or null if not yet connected
     */
    public String getClientNick() {
        return clientNick;
    }

    /**
     * Thread-safe helper writing a message to the output stream and clearing reference cache.
     *
     * @param message the message object to serialize
     * @throws IOException if network transmission fails
     */
    private synchronized void writeMessage(MessageDTO message) throws IOException {
        if (out != null) {
            out.writeObject(message);
            out.flush();
            out.reset();
        }
    }

    /**
     * Connects to the SafeChat server and attempts nickname registration.
     *
     * @param host the remote server hostname or IP address
     * @param port the remote server TCP port (1–65535)
     * @param nick the requested unique nickname
     * @return true if connection and registration succeeded (server responded with {@code JOIN_OK}); false otherwise
     */
    public boolean connect(String host, int port, String nick) {
        try {
            socket = new Socket(host, port);
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in = new ObjectInputStream(socket.getInputStream());

            in.setObjectInputFilter(filterInfo -> {
                Class<?> clazz = filterInfo.serialClass();
                if (clazz == null) return ObjectInputFilter.Status.UNDECIDED;
                if (clazz.getName().startsWith("com.safechat.shared.")) return ObjectInputFilter.Status.ALLOWED;
                if (clazz.isArray() || clazz.getName().startsWith("java.lang.")) return ObjectInputFilter.Status.ALLOWED;
                return ObjectInputFilter.Status.REJECTED;
            });

            MessageDTO joinMsg = new MessageDTO(
                    MessageDTO.MessageType.JOIN, nick, "ALL", "", cryptoService.getPublicKeyBytes());
            writeMessage(joinMsg);

            MessageDTO response = (MessageDTO) in.readObject();
            if (response.getType() == MessageDTO.MessageType.JOIN_OK) {
                this.clientNick = nick;
                startChunkCleanup();
                startListening();
                return true;
            } else {
                disconnect();
                return false;
            }
        } catch (java.net.ConnectException e) {
            onConnectionError.accept("Server is offline or unreachable.");
            disconnect();
            return false;
        } catch (java.net.UnknownHostException e) {
            onConnectionError.accept("Invalid IP address or unknown host.");
            disconnect();
            return false;
        } catch (IllegalArgumentException e) {
            onConnectionError.accept("Port number out of range (1-65535).");
            disconnect();
            return false;
        } catch (Exception e) {
            onConnectionError.accept("Unexpected error: " + e.getMessage());
            disconnect();
            return false;
        }
    }

    /**
     * Spawns a daemon worker thread listening for inbound messages from the server socket.
     */
    private void startListening() {
        Thread listenerThread = new Thread(() -> {
            try {
                while (true) {
                    MessageDTO receivedMessage = (MessageDTO) in.readObject();

                    if (receivedMessage.getPublicKey() != null && receivedMessage.getSender() != null) {
                        if (!receivedMessage.getSender().equals(clientNick)) {
                            cryptoService.storePublicKey(receivedMessage.getSender(), receivedMessage.getPublicKey());
                        }
                    }

                    if (receivedMessage.getType() == MessageDTO.MessageType.READ_RECEIPT) {
                        if (receivedMessage.getEncryptedPayload() != null && cryptoService.hasAesKey(receivedMessage.getSender())) {
                            try {
                                cryptoService.decryptMessage(receivedMessage.getEncryptedPayload(),
                                        cryptoService.getAesKey(receivedMessage.getSender()));
                            } catch (Exception e) {
                                System.err.println("[NET] Failed to decrypt read receipt from: " + receivedMessage.getSender());
                                continue;
                            }
                        }
                        onMessageReceived.accept(receivedMessage);
                    } else if (receivedMessage.getType() == MessageDTO.MessageType.KEY_EXCHANGE) {
                        if (!receivedMessage.getSender().equals(clientNick)) {
                            handleKeyExchange(receivedMessage);
                        }
                    } else if (receivedMessage.getType() == MessageDTO.MessageType.CHAT) {
                        handleChatMessage(receivedMessage);
                    } else {
                        onMessageReceived.accept(receivedMessage);
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                onConnectionError.accept("Disconnected from server.");
                disconnect();
            }
        });
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    /**
     * Broadcasts a public message to all users on the server, fragmenting into chunks if needed.
     *
     * @param text the plaintext message to send
     */
    public void sendBroadcastMessage(String text) {
        try {
            List<String> chunks = splitText(text);

            if (chunks.size() == 1) {
                MessageDTO chatMessage = new MessageDTO(MessageDTO.MessageType.CHAT, clientNick, "ALL", text);
                writeMessage(chatMessage);
            } else {
                String messageId = UUID.randomUUID().toString();
                for (int i = 0; i < chunks.size(); i++) {
                    MessageDTO chunkMsg = new MessageDTO(MessageDTO.MessageType.CHAT, clientNick, "ALL", chunks.get(i))
                            .withChunkInfo(messageId, i, chunks.size());
                    writeMessage(chunkMsg);
                }
            }
        } catch (IOException e) {
            onConnectionError.accept("Sending error: " + e.getMessage());
        }
    }

    /**
     * Sends an End-to-End encrypted private message to a specific recipient.
     * <p>
     * Negotiates a new AES session key if one has not yet been exchanged with this recipient,
     * encrypts each fragment using AES-GCM, and dispatches them across the network.
     *
     * @param recipientNick the nickname of the recipient
     * @param plainText     the plaintext message content
     */
    public void sendPrivateMessage(String recipientNick, String plainText) {
        try {
            if (!cryptoService.hasPublicKey(recipientNick)) {
                onConnectionError.accept("No public key for: " + recipientNick);
                return;
            }

            if (!cryptoService.hasAesKey(recipientNick)) {
                SecretKey aesKey = cryptoService.generateAesKey();
                cryptoService.storeAesKey(recipientNick, aesKey);
                byte[] encryptedAesKey = cryptoService.encryptAesKey(aesKey, recipientNick);

                MessageDTO keyExchangeMsg = new MessageDTO(MessageDTO.MessageType.KEY_EXCHANGE, clientNick,
                                recipientNick, encryptedAesKey);
                writeMessage(keyExchangeMsg);
            }

            SecretKey aesKey = cryptoService.getAesKey(recipientNick);
            List<String> chunks = splitText(plainText);

            if (chunks.size() == 1) {
                byte[] encryptedContent = cryptoService.encryptMessage(plainText, aesKey);
                MessageDTO chatMessage = new MessageDTO(MessageDTO.MessageType.CHAT, clientNick, recipientNick,
                        encryptedContent);
                writeMessage(chatMessage);
            } else {
                String messageId = UUID.randomUUID().toString();
                for (int i = 0; i < chunks.size(); i++) {
                    byte[] encryptedChunk = cryptoService.encryptMessage(chunks.get(i), aesKey);
                    MessageDTO chunkMsg = new MessageDTO(MessageDTO.MessageType.CHAT, clientNick, recipientNick,
                            encryptedChunk).withChunkInfo(messageId, i, chunks.size());
                    writeMessage(chunkMsg);
                }
            }

        } catch (Exception e) {
            onConnectionError.accept("Encryption error: " + e.getMessage());
        }
    }

    /**
     * Sends an encrypted read receipt to notify the sender that their private message was viewed.
     *
     * @param recipientNick the nickname of the sender to notify
     */
    public void sendReadReceipt(String recipientNick) {
        try {
            MessageDTO receipt;
            if (cryptoService.hasAesKey(recipientNick)) {
                SecretKey aesKey = cryptoService.getAesKey(recipientNick);
                byte[] encryptedPayload = cryptoService.encryptMessage("READ", aesKey);
                receipt = new MessageDTO(MessageDTO.MessageType.READ_RECEIPT, clientNick, recipientNick, encryptedPayload);
            } else {
                receipt = new MessageDTO(MessageDTO.MessageType.READ_RECEIPT, clientNick, recipientNick, "");
            }
            writeMessage(receipt);
        } catch (Exception e) {
            System.err.println("[NET] Read receipt send failed: " + e.getMessage());
        }
    }

    /**
     * Handles an incoming key exchange message by decrypting the session key with our RSA private key.
     */
    private void handleKeyExchange(MessageDTO message) {
        try {
            SecretKey aesKey = cryptoService.decryptAesKey(message.getEncryptedPayload());
            cryptoService.storeAesKey(message.getSender(), aesKey);
        } catch (Exception e) {
            onConnectionError.accept("Exchanging keys error: " + message.getSender());
        }
    }

    /**
     * Processes an incoming CHAT message, buffering and assembling chunks if fragmented.
     */

    private void handleChatMessage(MessageDTO message) {
        try {
            if (message.getTotalChunks() <= 1) {
                deliverDecryptedMessage(message);
                return;
            }

            String msgId = message.getMessageId();
            if (msgId == null) {
                return;
            }

            String chunkText;
            if (message.getEncryptedPayload() != null) {
                String sender = message.getSender();
                String keyOwner = sender.equals(clientNick) ? message.getRecipient() : sender;
                SecretKey aesKey = cryptoService.getAesKey(keyOwner);
                if (aesKey == null) {
                    return;
                }
                chunkText = cryptoService.decryptMessage(message.getEncryptedPayload(), aesKey);
            } else {
                chunkText = message.getContent();
            }

            chunkBuffer.computeIfAbsent(msgId, k -> new ConcurrentHashMap<>()).put(message.getChunkIndex(), chunkText);
            chunkMetadata.putIfAbsent(msgId, message);
            chunkTimestamps.putIfAbsent(msgId, System.currentTimeMillis());
            chunkExpected.putIfAbsent(msgId, message.getTotalChunks());

            Map<Integer, String> chunks = chunkBuffer.get(msgId);
            int expected = chunkExpected.get(msgId);

            if (chunks.size() == expected) {
                StringBuilder fullText = new StringBuilder();
                for (int i = 0; i < expected; i++) {
                    String part = chunks.get(i);
                    if (part != null) {
                        fullText.append(part);
                    }
                }
                MessageDTO originalMeta = chunkMetadata.get(msgId);
                MessageDTO fullMessage = new MessageDTO(
                        MessageDTO.MessageType.CHAT,
                        originalMeta.getSender(),
                        originalMeta.getRecipient(),
                        fullText.toString());

                onMessageReceived.accept(fullMessage);

                cleanupChunkData(msgId);
            }

        } catch (Exception e) {
            onConnectionError.accept("Error processing message.");
        }
    }

    /**
     * Decrypts a non-fragmented incoming message and passes it to the UI callback.
     */
    private void deliverDecryptedMessage(MessageDTO message) {
        try {
            if (message.getEncryptedPayload() != null) {
                String sender = message.getSender();
                String keyOwner = sender.equals(clientNick) ? message.getRecipient() : sender;
                SecretKey aesKey = cryptoService.getAesKey(keyOwner);

                if (aesKey != null) {
                    String decryptedText = cryptoService.decryptMessage(message.getEncryptedPayload(), aesKey);
                    MessageDTO decryptedMessage = new MessageDTO(MessageDTO.MessageType.CHAT, sender,
                            message.getRecipient(), decryptedText);
                    onMessageReceived.accept(decryptedMessage);
                }
            } else {
                onMessageReceived.accept(message);
            }
        } catch (Exception e) {
            onConnectionError.accept("Error decrypting message.");
        }
    }

    /**
     * Splits a text string into slices of at most {@link MessageDTO#MAX_CHUNK_SIZE} characters.
     */
    private List<String> splitText(String text) {
        int maxSize = MessageDTO.MAX_CHUNK_SIZE;
        if (text.length() <= maxSize) {
            return Collections.singletonList(text);
        }

        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += maxSize) {
            int end = Math.min(i + maxSize, text.length());
            chunks.add(text.substring(i, end));
        }

        if (chunks.size() > MessageDTO.MAX_TOTAL_CHUNKS) {
            chunks = chunks.subList(0, MessageDTO.MAX_TOTAL_CHUNKS);
        }

        return chunks;
    }

    /**
     * Schedules periodic pruning of uncompleted fragmented message buffers.
     */
    private void startChunkCleanup() {
        chunkCleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChunkCleanup");
            t.setDaemon(true);
            return t;
        });

        chunkCleanupScheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            List<String> expired = new ArrayList<>();

            for (Map.Entry<String, Long> entry : chunkTimestamps.entrySet()) {
                if (now - entry.getValue() > CHUNK_TIMEOUT_MS) {
                    expired.add(entry.getKey());
                }
            }

            for (String msgId : expired) {
                System.out.println("[CHUNK] Timeout - cleaning incomplete message: " + msgId);
                cleanupChunkData(msgId);
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * Removes buffer state associated with an assembled or expired message.
     */
    private void cleanupChunkData(String msgId) {
        chunkBuffer.remove(msgId);
        chunkMetadata.remove(msgId);
        chunkTimestamps.remove(msgId);
        chunkExpected.remove(msgId);
    }

    /**
     * Gracefully terminates background schedulers and closes TCP networking streams.
     */
    public void disconnect() {
        try {
            if (chunkCleanupScheduler != null) {
                chunkCleanupScheduler.shutdownNow();
            }
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
