package com.safechat.client;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cryptographic service providing End-to-End Encryption (E2EE) for the SafeChat client.
 * <p>
 * Implements a hybrid cryptosystem combining:
 * <ul>
 *   <li><b>RSA 2048-bit </b>: for asymmetric peer public key
 *       exchange and secure encryption/decryption of AES session keys.</li>
 *   <li><b>AES 256-bit GCM </b>: for authenticated symmetric encryption of chat text and read receipts, providing both
 *       confidentiality and integrity.</li>
 * </ul>
 * Also manages thread-safe in-memory stores for peer RSA public keys and negotiated AES session keys.
 */
public class CryptoService {

    private static final String RSA_ALGORITHM = "RSA";
    private static final String RSA_CIPHER_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final int RSA_KEY_SIZE = 2048;

    private static final String AES_ALGORITHM = "AES";
    private static final String AES_CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int AES_KEY_SIZE = 256;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    private final Map<String, PublicKey> publicKeyStore = new ConcurrentHashMap<>();
    private final Map<String, SecretKey> aesKeyStore = new ConcurrentHashMap<>();

    /**
     * Constructs a new CryptoService, automatically generating an RSA 2048-bit key pair for this client.
     *
     * @throws RuntimeException if the RSA key pair generator is unavailable on the platform
     */
    public CryptoService() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(RSA_ALGORITHM);
            keyPairGenerator.initialize(RSA_KEY_SIZE);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            this.privateKey = keyPair.getPrivate();
            this.publicKey = keyPair.getPublic();
            System.out.println("[CRYPTO] RSA key pair generated (2048-bit)");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Fatal: RSA algorithm not available", e);
        }
    }

    /**
     * Returns this client's RSA public key encoded in X.509 format.
     *
     * @return DER-encoded public key bytes
     */
    public byte[] getPublicKeyBytes() {
        return publicKey.getEncoded();
    }

    /**
     * Deserializes and stores an RSA public key for a remote user.
     *
     * @param nick           user's nickname
     * @param publicKeyBytes DER-encoded public key bytes
     */
    public void storePublicKey(String nick, byte[] publicKeyBytes) {
        try {
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(RSA_ALGORITHM);
            PublicKey restoredKey = keyFactory.generatePublic(keySpec);
            publicKeyStore.put(nick, restoredKey);
            System.out.println("[CRYPTO] Stored public key for user: " + nick);
        } catch (Exception e) {
            System.err.println("[CRYPTO] Error storing public key for " + nick + ": " + e.getMessage());
        }
    }

    /**
     * Checks if an RSA public key has been registered for the specified user.
     *
     * @param nick user's nickname
     * @return true if the public key is present; false otherwise
     */
    public boolean hasPublicKey(String nick) {
        return publicKeyStore.containsKey(nick);
    }

    /**
     * Retrieves the stored RSA public key for the specified user.
     *
     * @param nick user's nickname
     * @return the {@link PublicKey}, or null if not registered
     */
    public PublicKey getPublicKey(String nick) {
        return publicKeyStore.get(nick);
    }

    /**
     * Generates a cryptographically strong, random 256-bit AES symmetric key.
     *
     * @return a newly generated {@link SecretKey}
     * @throws RuntimeException if AES key generation fails
     */
    public SecretKey generateAesKey() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(AES_ALGORITHM);
            keyGenerator.init(AES_KEY_SIZE);
            return keyGenerator.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Fatal: AES algorithm not available", e);
        }
    }

    /**
     * Stores an established AES session key for a private conversation with a peer.
     *
     * @param nick   the peer's nickname
     * @param aesKey the negotiated {@link SecretKey}
     */
    public void storeAesKey(String nick, SecretKey aesKey) {
        aesKeyStore.put(nick, aesKey);
        System.out.println("[CRYPTO] AES session key stored for user: " + nick);
    }

    /**
     * Checks if an active AES session key exists for conversation with the specified peer.
     *
     * @param nick the peer's nickname
     * @return true if an AES key is stored; false otherwise
     */
    public boolean hasAesKey(String nick) {
        return aesKeyStore.containsKey(nick);
    }

    /**
     * Retrieves the active AES session key for the specified peer.
     *
     * @param nick the peer's nickname
     * @return the {@link SecretKey}, or null if none established
     */
    public SecretKey getAesKey(String nick) {
        return aesKeyStore.get(nick);
    }

    /**
     * Encrypts a 256-bit AES session key using the recipient's RSA public key.
     *
     * @param aesKey        the AES session key to encrypt
     * @param recipientNick the nickname of the recipient whose public key will be used
     * @return the RSA-OAEP encrypted AES key bytes
     * @throws IllegalStateException if the recipient's RSA public key is not registered
     * @throws RuntimeException      if encryption fails
     */
    public byte[] encryptAesKey(SecretKey aesKey, String recipientNick) {
        try {
            PublicKey recipientPublicKey = publicKeyStore.get(recipientNick);
            if (recipientPublicKey == null) {
                throw new IllegalStateException("No public key found for user: " + recipientNick);
            }
            Cipher cipher = Cipher.getInstance(RSA_CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, recipientPublicKey);
            return cipher.doFinal(aesKey.getEncoded());
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                | IllegalBlockSizeException | BadPaddingException e) {
            throw new RuntimeException("Error encrypting AES key for " + recipientNick + ": " + e.getMessage(), e);
        }
    }

    /**
     * Decrypts an incoming AES session key using this client's RSA private key.
     *
     * @param encryptedAesKey the RSA-OAEP encrypted AES key bytes
     * @return the decrypted {@link SecretKey}
     * @throws RuntimeException if decryption fails
     */
    public SecretKey decryptAesKey(byte[] encryptedAesKey) {
        try {
            Cipher cipher = Cipher.getInstance(RSA_CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] decryptedKeyBytes = cipher.doFinal(encryptedAesKey);
            return new SecretKeySpec(decryptedKeyBytes, AES_ALGORITHM);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                | IllegalBlockSizeException | BadPaddingException e) {
            throw new RuntimeException("Error decrypting AES key: " + e.getMessage(), e);
        }
    }

    /**
     * Encrypts plaintext message content using AES-256 in GCM mode
     * <p>
     * A cryptographically random 12-byte IV is generated and prepended to the output byte array,
     * followed by the ciphertext and the 128-bit authentication tag.
     *
     * @param plainText the UTF-8 text to encrypt
     * @param aesKey    the shared AES secret key
     * @return combined byte array containing: {@code [12 bytes IV] + [ciphertext & 16-byte GCM tag]}
     * @throws RuntimeException if encryption fails
     */
    public byte[] encryptMessage(String plainText, SecretKey aesKey) {
        try {
            Cipher cipher = Cipher.getInstance(AES_CIPHER_TRANSFORMATION);

            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom secureRandom = new SecureRandom();
            secureRandom.nextBytes(iv);

            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec);

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] result = new byte[GCM_IV_LENGTH + cipherText.length];
            System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH);
            System.arraycopy(cipherText, 0, result, GCM_IV_LENGTH, cipherText.length);

            return result;
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
            throw new RuntimeException("Error encrypting message: " + e.getMessage(), e);
        }
    }

    /**
     * Decrypts an AES-256 GCM encrypted message and verifies its authentication tag.
     *
     * @param cipherData combined byte array containing {@code [12 bytes IV] + [ciphertext & tag]}
     * @param aesKey     the shared AES secret key
     * @return the decrypted UTF-8 plaintext string
     * @throws RuntimeException if authentication fails or decryption encounters an error
     */
    public String decryptMessage(byte[] cipherData, SecretKey aesKey) {
        try {
            Cipher cipher = Cipher.getInstance(AES_CIPHER_TRANSFORMATION);

            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(cipherData, 0, iv, 0, GCM_IV_LENGTH);

            byte[] cipherText = new byte[cipherData.length - GCM_IV_LENGTH];
            System.arraycopy(cipherData, GCM_IV_LENGTH, cipherText, 0, cipherText.length);

            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, gcmSpec);

            byte[] decryptedBytes = cipher.doFinal(cipherText);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
            throw new RuntimeException("Error decrypting message: " + e.getMessage(), e);
        }
    }
}