package com.safechat.shared;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable Data Transfer Object (DTO) used for all network communication in SafeChat.
 * <p>
 * This class encapsulates message payloads exchanged between clients and the server via
 * serialized TCP sockets. It supports:
 * <ul>
 *   <li>Standard chat and system command messages (via {@link MessageType}).</li>
 *   <li>Zero-Knowledge End-to-End Encryption (E2EE) using opaque byte arrays
 *       for ciphertext and public keys.</li>
 *   <li>Chunking metadata for splitting large payloads into ordered fragments.</li>
 *   <li>Defensive copying of mutable array structures to guarantee immutability.</li>
 * </ul>
 * Instances can be constructed via convenience constructors, static factory methods,
 * or the {@link Builder} pattern.
 */
public final class MessageDTO implements Serializable {

    /**
     * Enumeration of all supported message protocol types.
     */
    public enum MessageType {
        /** Standard chat message */
        CHAT,
        /** Client registration request containing nickname and RSA public key. */
        JOIN,
        /** Notification that a client has disconnected from the chat. */
        LEAVE,
        /** Server error response indicating nickname validation or collision failure. */
        NICK_ERROR,
        /** Server confirmation that client registration was successful. */
        JOIN_OK,
        /** Key exchange packet carrying an RSA-encrypted AES session key. */
        KEY_EXCHANGE,
        /** Encrypted read receipt indicating message delivery and display. */
        READ_RECEIPT
    }

    private static final long serialVersionUID = 4L;

    /** Maximum character length permitted per individual message chunk. */
    public static final int MAX_CHUNK_SIZE = 10000;

    /** Maximum number of chunks allowed for a single fragmented message. */
    public static final int MAX_TOTAL_CHUNKS = 10; 

    /** The operational type of this message. */
    private final MessageType type;

    /** Sender's nickname or "Server" for system messages. */
    private final String sender;

    /** Target recipient nickname, or "ALL" for public broadcast. */
    private final String recipient;

    /** Plaintext content of unencrypted messages or system alerts. */
    private final String content; 

    /** Ciphertext payload (AES-GCM encrypted text or RSA-encrypted AES session key) */
    private final byte[] encryptedPayload;

    /** encoded RSA public key bytes, sent during JOIN handshake */
    private final byte[] publicKey;

    /** Unique identifier correlating fragmented chunks of a single message. */
    private final String messageId;

    /** Zero-based index of this fragment within the chunk sequence. */
    private final int chunkIndex;

    /** Total number of chunks comprising the complete message. */
    private final int totalChunks;

    /** Epoch timestamp in milliseconds indicating message creation time. */
    private final long timestamp;

    /**
     * Private canonical constructor initializing all message attributes.
     */
    private MessageDTO(MessageType type, String sender, String recipient, String content,
                       byte[] encryptedPayload, byte[] publicKey,
                       String messageId, int chunkIndex, int totalChunks, long timestamp) {
        this.type = Objects.requireNonNull(type, "MessageType cannot be null");
        this.sender = sender;
        this.recipient = recipient;
        this.content = content;
        this.encryptedPayload = encryptedPayload != null ? encryptedPayload.clone() : null;
        this.publicKey = publicKey != null ? publicKey.clone() : null;
        this.messageId = messageId;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks > 0 ? totalChunks : 1;
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    /**
     * Convenience constructor for plaintext messages.
     *
     * @param type      the message type
     * @param sender    the nickname of the sender
     * @param recipient the nickname of the recipient or "ALL"
     * @param content   the plaintext content
     */
    public MessageDTO(MessageType type, String sender, String recipient, String content) {
        this(type, sender, recipient, content, null, null, null, 0, 1, System.currentTimeMillis());
    }

    /**
     * Convenience constructor for encrypted messages.
     *
     * @param type             the message type
     * @param sender           the nickname of the sender
     * @param recipient        the nickname of the recipient
     * @param encryptedPayload the ciphertext or encrypted key payload
     */
    public MessageDTO(MessageType type, String sender, String recipient, byte[] encryptedPayload) {
        this(type, sender, recipient, null, encryptedPayload, null, null, 0, 1, System.currentTimeMillis());
    }

    /**
     * Convenience constructor for registration messages carrying an RSA public key.
     *
     * @param type      the message type
     * @param sender    the nickname of the joining client
     * @param recipient the target recipient ("ALL")
     * @param content   optional descriptive text
     * @param publicKey the X.509-encoded RSA public key
     */
    public MessageDTO(MessageType type, String sender, String recipient, String content, byte[] publicKey) {
        this(type, sender, recipient, content, null, publicKey, null, 0, 1, System.currentTimeMillis());
    }

    /**
     * Returns a new {@code MessageDTO} instance with updated chunking metadata.
     *
     * @param messageId   the unique grouping ID for the message chunks
     * @param chunkIndex  the zero-based index of this chunk
     * @param totalChunks the total number of chunks
     * @return a new immutable {@code MessageDTO} with the specified chunk properties
     */
    public MessageDTO withChunkInfo(String messageId, int chunkIndex, int totalChunks) {
        return new MessageDTO(this.type, this.sender, this.recipient, this.content,
                this.encryptedPayload, this.publicKey, messageId, chunkIndex, totalChunks, this.timestamp);
    }

    /**
     * Factory method for creating a plaintext chat message.
     */
    public static MessageDTO createChat(String sender, String recipient, String content) {
        return new MessageDTO(MessageType.CHAT, sender, recipient, content);
    }

    /**
     * Factory method for creating an encrypted chat message.
     */
    public static MessageDTO createEncryptedChat(String sender, String recipient, byte[] encryptedPayload) {
        return new MessageDTO(MessageType.CHAT, sender, recipient, encryptedPayload);
    }

    /**
     * Factory method for creating a client join message with public key.
     */
    public static MessageDTO createJoin(String sender, byte[] publicKey) {
        return new MessageDTO(MessageType.JOIN, sender, "ALL", "", publicKey);
    }

    /**
     * Factory method for creating a client leave message.
     */
    public static MessageDTO createLeave(String sender) {
        return new MessageDTO(MessageType.LEAVE, sender, "ALL", sender + " left the chat");
    }

    /**
     * Factory method for creating a successful registration confirmation message.
     */
    public static MessageDTO createJoinOk(String recipient) {
        return new MessageDTO(MessageType.JOIN_OK, "Server", recipient, "OK");
    }

    /**
     * Factory method for creating a nickname validation error message.
     */
    public static MessageDTO createNickError(String recipient, String message) {
        return new MessageDTO(MessageType.NICK_ERROR, "Server", recipient, message);
    }

    /**
     * Factory method for creating an RSA-encrypted key exchange message.
     */
    public static MessageDTO createKeyExchange(String sender, String recipient, byte[] encryptedAesKey) {
        return new MessageDTO(MessageType.KEY_EXCHANGE, sender, recipient, encryptedAesKey);
    }

    /**
     * Factory method for creating an encrypted read receipt message.
     */
    public static MessageDTO createReadReceipt(String sender, String recipient, byte[] encryptedPayload) {
        return new MessageDTO(MessageType.READ_RECEIPT, sender, recipient, encryptedPayload);
    }

    // gettery

    /** @return the protocol message type */
    public MessageType getType() {
        return type;
    }

    /** @return the nickname of the sender */
    public String getSender() {
        return sender;
    }

    /** @return the nickname of the recipient, or "ALL" */
    public String getRecipient() {
        return recipient;
    }

    /** @return the plaintext content or null for encrypted messages */
    public String getContent() {
        return content;
    }

    /** @return the message creation epoch timestamp in milliseconds */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Returns a defensive clone of the encrypted payload bytes.
     *
     * @return a copy of the ciphertext byte array, or null if unencrypted
     */
    public byte[] getEncryptedPayload() {
        return encryptedPayload != null ? encryptedPayload.clone() : null;
    }

    /**
     * Returns a defensive clone of the RSA public key bytes.
     *
     * @return a copy of public key byte array, or null
     */
    public byte[] getPublicKey() {
        return publicKey != null ? publicKey.clone() : null;
    }

    /** @return the unique grouping identifier for fragmented messages */
    public String getMessageId() {
        return messageId;
    }

    /** @return the zero-based fragment index */
    public int getChunkIndex() {
        return chunkIndex;
    }

    /** @return the total number of fragments comprising this message */
    public int getTotalChunks() {
        return totalChunks;
    }

    @Override
    public String toString() {
        String chunkInfo = totalChunks > 1 ? " [chunk " + (chunkIndex + 1) + "/" + totalChunks + "]" : "";
        if (encryptedPayload != null) {
            return "[" + sender + " -> " + recipient + "]: [encrypted, " + encryptedPayload.length + " bytes]"
                    + chunkInfo;
        }
        return "[" + sender + " -> " + recipient + "]: " + content + chunkInfo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MessageDTO that = (MessageDTO) o;
        return chunkIndex == that.chunkIndex &&
                totalChunks == that.totalChunks &&
                timestamp == that.timestamp &&
                type == that.type &&
                Objects.equals(sender, that.sender) &&
                Objects.equals(recipient, that.recipient) &&
                Objects.equals(content, that.content) &&
                Arrays.equals(encryptedPayload, that.encryptedPayload) &&
                Arrays.equals(publicKey, that.publicKey) &&
                Objects.equals(messageId, that.messageId);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(type, sender, recipient, content, messageId, chunkIndex, totalChunks, timestamp);
        result = 31 * result + Arrays.hashCode(encryptedPayload);
        result = 31 * result + Arrays.hashCode(publicKey);
        return result;
    }

    /**
     * Fluent builder for constructing immutable {@link MessageDTO} instances.
     */
    public static class Builder {
        private final MessageType type;
        private final String sender;
        private final String recipient;
        private String content;
        private byte[] encryptedPayload;
        private byte[] publicKey;
        private String messageId;
        private int chunkIndex = 0;
        private int totalChunks = 1;
        private long timestamp = System.currentTimeMillis();

        /**
         * Initializes a builder with mandatory routing headers.
         *
         * @param type      the message protocol type
         * @param sender    the nickname of the sender
         * @param recipient the target recipient or "ALL"
         */
        public Builder(MessageType type, String sender, String recipient) {
            this.type = Objects.requireNonNull(type, "type cannot be null");
            this.sender = sender;
            this.recipient = recipient;
        }

        /** Sets plaintext content. Mutually exclusive with encryptedPayload. */
        public Builder content(String content) {
            if (this.encryptedPayload != null && content != null) {
                throw new IllegalStateException("Cannot set content when encryptedPayload is already set");
            }
            this.content = content;
            return this;
        }

        /** Sets ciphertext payload */
        public Builder encryptedPayload(byte[] payload) {
            if (this.content != null && payload != null) {
                throw new IllegalStateException("Cannot set encryptedPayload when content is already set");
            }
            this.encryptedPayload = payload != null ? payload.clone() : null;
            return this;
        }

        /** Sets RSA public key bytes */
        public Builder publicKey(byte[] publicKey) {
            this.publicKey = publicKey != null ? publicKey.clone() : null;
            return this;
        }

        /** Sets unique identifier correlating message chunks. */
        public Builder messageId(String messageId) {
            this.messageId = messageId;
            return this;
        }

        /** Sets the zero-based chunk index. */
        public Builder chunkIndex(int chunkIndex) {
            this.chunkIndex = chunkIndex;
            return this;
        }

        /** Sets the total chunk count. */
        public Builder totalChunks(int totalChunks) {
            this.totalChunks = totalChunks;
            return this;
        }

        /** Sets message timestamp. */
        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /** Sets chunking parameters simultaneously. */
        public Builder chunkInfo(String messageId, int chunkIndex, int totalChunks) {
            this.messageId = messageId;
            this.chunkIndex = chunkIndex;
            this.totalChunks = totalChunks;
            return this;
        }

        /**
         * Validates and instantiates the immutable {@link MessageDTO}.
         *
         * @return a new {@code MessageDTO} instance
         * @throws IllegalStateException if both plaintext content and encryptedPayload are set
         */
        public MessageDTO build() {
            if (content != null && encryptedPayload != null) {
                throw new IllegalStateException("Message cannot contain both content and encryptedPayload");
            }
            return new MessageDTO(type, sender, recipient, content, encryptedPayload, publicKey,
                    messageId, chunkIndex, totalChunks, timestamp);
        }
    }
}