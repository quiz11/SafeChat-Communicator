package com.safechat.client;

import com.safechat.shared.MessageDTO;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Model-View-Controller (MVC) presenter for the SafeChat JavaFX interface.
 * <p>
 * Manages view interactions configured in {@code /chat-view.fxml}:
 * <ul>
 *   <li>Handles login/connection state transitions, port parsing, and input validation.</li>
 *   <li>Maintains local chat history partitions indexed by recipient room (public "ALL" vs. private contacts).</li>
 *   <li>Dynamically renders formatted chat bubbles with distinct styles for own, peer, and system messages.</li>
 *   <li>Tracks unread conversation activity and updates list cell highlight indicators.</li>
 *   <li>Dispatches read receipt confirmations upon opening unread private chats.</li>
 *   <li>Guarantees UI mutations occur exclusively on the JavaFX Application Thread via {@link Platform#runLater(Runnable)}.</li>
 * </ul>
 */
public class ChatController {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault());

    /**
     * Internal domain representation of an individual rendered chat entry.
     */
    private static class ChatMessage {
        final String sender;
        final String content;
        final boolean isSystem;
        final long timestamp;
        boolean readByRecipient;

        ChatMessage(String sender, String content, boolean isSystem, long timestamp) {
            this.sender = sender;
            this.content = content;
            this.isSystem = isSystem;
            this.timestamp = timestamp;
            this.readByRecipient = false;
        }

        ChatMessage(String sender, String content, boolean isSystem) {
            this(sender, content, isSystem, System.currentTimeMillis());
        }
    }

    @FXML
    private VBox loginPanel;
    @FXML
    private BorderPane chatPanel;
    @FXML
    private TextField hostField, portField, nickField, messageField;
    @FXML
    private Button connectButton;
    @FXML
    private Label errorLabel, currentChatLabel, loggedInUserLabel;
    @FXML
    private ScrollPane chatScrollPane;
    @FXML
    private VBox chatMessagesBox;
    @FXML
    private ListView<String> usersList;

    private NetworkService networkService;
    private String currentRecipient = "ALL";
    private final Map<String, List<ChatMessage>> messageHistoryMap = new HashMap<>();

    private final Map<String, Long> lastMessageTime = new HashMap<>();
    private final Set<String> unreadChats = new HashSet<>();
    private final Map<String, Boolean> lastMessageReadStatus = new HashMap<>();

    /**
     * JavaFX lifecycle initialization callback invoked after FXML hierarchy is loaded.
     */
    @FXML
    public void initialize() {
        messageHistoryMap.put("ALL", new ArrayList<>());
        lastMessageTime.put("ALL", System.currentTimeMillis());
        usersList.getItems().add("ALL");

        usersList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    if (unreadChats.contains(item)) {
                        setStyle("-fx-border-color: #5abc7cff; -fx-border-width: 2; "
                                + "-fx-border-radius: 4; -fx-background-color: #baebb3ff; "
                                + "-fx-font-weight: bold;");
                    } else {
                        setStyle("");
                    }
                }
            }
        });

        usersList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null)
                return;

            currentRecipient = newVal;
            if (currentRecipient.equals("ALL")) {
                currentChatLabel.setText("General chat (ALL)");
            } else {
                currentChatLabel.setText("Private chat with: " + currentRecipient);

                List<ChatMessage> history = messageHistoryMap.get(newVal);
                if (history != null && !history.isEmpty()) {
                    ChatMessage lastMsg = history.get(history.size() - 1);
                    if (!lastMsg.isSystem && !lastMsg.sender.equals(networkService.getClientNick())) {
                        networkService.sendReadReceipt(newVal);
                    }
                }
            }

            unreadChats.remove(newVal);
            usersList.refresh();

            refreshChatDisplay();
        });

        networkService = new NetworkService(
                this::onMessageReceived,
                this::onConnectionError);
    }

    /**
     * Handles the "Connect" action triggered from the login screen.
     * <p>
     * Validates host, port range, and nickname syntax, disables the button to prevent duplicate
     * connection attempts, and spawns a background thread to execute the network handshake.
     */
    @FXML
    public void handleConnect() {
        String host = hostField.getText().trim();
        String portStr = portField.getText().trim();
        String nick = nickField.getText().trim();

        if (host.isEmpty() || nick.isEmpty() || portStr.isEmpty()) {
            errorLabel.setText("Fill out every input");
            return;
        }

        if (!nick.matches("^[a-zA-Z0-9_]{1,32}$") || nick.equalsIgnoreCase("ALL") || nick.equalsIgnoreCase("Server")) {
            errorLabel.setText("Invalid nick (1-32 chars, not ALL/Server)");
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) {
                errorLabel.setText("Port number out of range (1-65535)");
                return;
            }
        } catch (NumberFormatException e) {
            errorLabel.setText("Invalid port number");
            return;
        }

        if (connectButton != null) {
            connectButton.setDisable(true);
        }
        errorLabel.setText("Connecting...");

        new Thread(() -> {
            boolean success = networkService.connect(host, port, nick);
            Platform.runLater(() -> {
                if (success) {
                    loginPanel.setVisible(false);
                    chatPanel.setVisible(true);
                    loggedInUserLabel.setText("Logged in as: " + nick);

                    addMessage("ALL", new ChatMessage("System", "Connected successfully as " + nick + "!", true));
                    refreshChatDisplay();
                } else {
                    if (connectButton != null) {
                        connectButton.setDisable(false);
                    }
                    errorLabel.setText("Unable to connect or invalid username.");
                }
            });
        }).start();
    }

    /**
     * Handles the "Send" action triggered when submitting message input.
     * <p>
     * Enforces total payload length limits, immediately updates sender's local history
     * for private chats, and delegates transmission to {@link NetworkService}.
     */
    @FXML
    public void handleSend() {
        String text = messageField.getText().trim();
        if (text.isEmpty())
            return;

        int maxLength = MessageDTO.MAX_CHUNK_SIZE * MessageDTO.MAX_TOTAL_CHUNKS;
        if (text.length() > maxLength) {
            addMessage(currentRecipient, new ChatMessage("System",
                    "Message too long. Max " + maxLength + " characters.", true));
            refreshChatDisplay();
            return;
        }

        if (currentRecipient.equals("ALL")) {
            networkService.sendBroadcastMessage(text);
        } else {
            String myNick = networkService.getClientNick();
            addMessage(currentRecipient, new ChatMessage(myNick, text, false, System.currentTimeMillis()));
            lastMessageReadStatus.put(currentRecipient, false);
            lastMessageTime.put(currentRecipient, System.currentTimeMillis());
            sortUsersList();
            refreshChatDisplay();

            networkService.sendPrivateMessage(currentRecipient, text);
        }

        messageField.clear();
    }

    /**
     * Inbound message processing callback invoked on the JavaFX application thread.
     * <p>
     * Handles read receipts, room directory discovery (JOIN/LEAVE events), chat message storage,
     * conversation sort order, and read receipt auto-reply generation.
     *
     * @param message the received protocol message
     */
    private void onMessageReceived(MessageDTO message) {
        Platform.runLater(() -> {
            if (message.getType() == MessageDTO.MessageType.READ_RECEIPT) {
                String roomKey = message.getSender();
                lastMessageReadStatus.put(roomKey, true);
                List<ChatMessage> history = messageHistoryMap.get(roomKey);
                if (history != null) {
                    String myNick = networkService.getClientNick();
                    for (int i = history.size() - 1; i >= 0; i--) {
                        if (history.get(i).sender.equals(myNick) && !history.get(i).isSystem) {
                            history.get(i).readByRecipient = true;
                            break;
                        }
                    }
                }
                if (roomKey.equals(currentRecipient)) {
                    refreshChatDisplay();
                }
                return;
            }

            if (message.getType() == MessageDTO.MessageType.JOIN) {
                String senderNick = message.getSender();
                if (!senderNick.equals(networkService.getClientNick()) && !usersList.getItems().contains(senderNick)) {
                    usersList.getItems().add(senderNick);
                    lastMessageTime.put(senderNick, 0L);
                }

                addMessage("ALL", new ChatMessage(senderNick, senderNick + " joined chat", true, message.getTimestamp()));

                lastMessageTime.put("ALL", System.currentTimeMillis());
                sortUsersList();

                if ("ALL".equals(currentRecipient)) {
                    refreshChatDisplay();
                } else {
                    unreadChats.add("ALL");
                    usersList.refresh();
                }
                return;
            }

            if (message.getType() == MessageDTO.MessageType.LEAVE) {
                String leftNick = message.getSender();
                usersList.getItems().remove(leftNick);
                lastMessageTime.remove(leftNick);
                unreadChats.remove(leftNick);
                lastMessageReadStatus.remove(leftNick);

                addMessage("ALL", new ChatMessage(leftNick, leftNick + " left the chat", true, message.getTimestamp()));
                lastMessageTime.put("ALL", System.currentTimeMillis());
                sortUsersList();

                if (leftNick.equals(currentRecipient)) {
                    addMessage(leftNick, new ChatMessage("System", leftNick + " has disconnected.", true, message.getTimestamp()));
                }
                refreshChatDisplay();
                return;
            }

            if (!"ALL".equals(message.getRecipient()) && message.getSender().equals(networkService.getClientNick())) {
                return;
            }

            String roomKey = "ALL";
            if (!"ALL".equals(message.getRecipient())) {
                String myNick = networkService.getClientNick();
                roomKey = message.getSender().equals(myNick) ? message.getRecipient() : message.getSender();
            }

            if (message.getSender().equals(networkService.getClientNick()) && !"ALL".equals(roomKey)) {
                lastMessageReadStatus.put(roomKey, false);
            }

            addMessage(roomKey, new ChatMessage(message.getSender(), message.getContent(), false, message.getTimestamp()));

            lastMessageTime.put(roomKey, System.currentTimeMillis());
            sortUsersList();

            if (roomKey.equals(currentRecipient)) {
                refreshChatDisplay();

                if (!"ALL".equals(roomKey) && !message.getSender().equals(networkService.getClientNick())) {
                    networkService.sendReadReceipt(message.getSender());
                }
            } else {
                unreadChats.add(roomKey);
                usersList.refresh();
            }
        });
    }

    /**
     * Appends a message entry to the conversation history map of the specified chat room.
     */
    private void addMessage(String roomKey, ChatMessage msg) {
        messageHistoryMap.computeIfAbsent(roomKey, k -> new ArrayList<>()).add(msg);
    }

    /**
     * Rebuilds the chat viewport children for the currently selected recipient and auto-scrolls to bottom.
     */
    private void refreshChatDisplay() {
        chatMessagesBox.getChildren().clear();
        List<ChatMessage> messages = messageHistoryMap.getOrDefault(currentRecipient, new ArrayList<>());
        String myNick = networkService != null ? networkService.getClientNick() : "";

        int lastOwnMsgIndex = -1;
        if (!"ALL".equals(currentRecipient) && !messages.isEmpty()) {
            ChatMessage lastMsg = messages.get(messages.size() - 1);
            if (lastMsg.sender.equals(myNick) && !lastMsg.isSystem) {
                lastOwnMsgIndex = messages.size() - 1;
            }
        }

        for (int i = 0; i < messages.size(); i++) {
            chatMessagesBox.getChildren().add(createMessageNode(messages.get(i), i == lastOwnMsgIndex));
        }
        Platform.runLater(() -> chatScrollPane.setVvalue(1.0));
    }

    /**
     * Instantiates a styled graphical container node ({@link HBox}) representing a message bubble.
     *
     * @param msg              the chat message model
     * @param isLastOwnMessage true if this node is the most recent outgoing message in a private chat
     * @return a configured JavaFX {@link Node}
     */
    private Node createMessageNode(ChatMessage msg, boolean isLastOwnMessage) {
        String myNick = networkService != null ? networkService.getClientNick() : "";
        String timeStr = formatTime(msg.timestamp);

        HBox container = new HBox();
        container.setMaxWidth(Double.MAX_VALUE);
        container.setSpacing(4);

        Label label = new Label();
        label.setWrapText(true);
        label.setMaxWidth(350);

        if (msg.isSystem) {
            label.setText(msg.content + "  [" + timeStr + "]");
            label.setStyle("-fx-padding: 4 10; -fx-text-fill: #888888; -fx-font-style: italic; "
                    + "-fx-font-family: 'Consolas';");
            container.setAlignment(Pos.CENTER);
        } else if (msg.sender.equals(myNick)) {
            label.setText(msg.content + "  [" + timeStr + "]");
            label.setStyle("-fx-padding: 6 12; -fx-background-color: #e8f4dfff; "
                    + "-fx-background-radius: 12 12 0 12; -fx-font-family: 'Consolas';");
            container.setAlignment(Pos.CENTER_RIGHT);

            if (isLastOwnMessage && !"ALL".equals(currentRecipient)) {
                Label indicator = new Label(msg.readByRecipient ? "\u25CF" : "\u25CB");
                indicator.setStyle("-fx-font-size: 10px; -fx-text-fill: "
                        + (msg.readByRecipient ? "#5abc7c" : "#aaaaaa") + ";");
                indicator.setMinWidth(12);
                container.getChildren().add(label);
                container.getChildren().add(indicator);
                return container;
            }
        } else {
            if ("ALL".equals(currentRecipient)) {
                label.setText("[" + msg.sender + "] " + msg.content + "  [" + timeStr + "]");
            } else {
                label.setText(msg.content + "  [" + timeStr + "]");
            }
            label.setStyle("-fx-padding: 6 12; -fx-background-color: rgb(245, 232, 232); "
                    + "-fx-background-radius: 12 12 12 0; -fx-font-family: 'Consolas'; "
                    + "-fx-border-color: #e0e0e0; -fx-border-radius: 12 12 12 0;");
            container.setAlignment(Pos.CENTER_LEFT);
        }

        container.getChildren().add(label);
        return container;
    }

    /**
     * Formats an epoch timestamp in milliseconds into a 24-hour {@code HH:mm} string.
     */
    private String formatTime(long epochMillis) {
        return TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis));
    }

    /**
     * Sorts contact list items in descending order of the most recent message timestamp.
     */
    private void sortUsersList() {
        ObservableList<String> items = usersList.getItems();
        FXCollections.sort(items, (a, b) -> {
            long timeA = lastMessageTime.getOrDefault(a, 0L);
            long timeB = lastMessageTime.getOrDefault(b, 0L);
            return Long.compare(timeB, timeA);
        });
    }

    /**
     * Displays a network error alert on the UI and unlocks the connect button if applicable.
     */
    private void onConnectionError(String errorMessage) {
        Platform.runLater(() -> {
            if (connectButton != null) {
                connectButton.setDisable(false);
            }
            errorLabel.setText(errorMessage);
            addMessage(currentRecipient, new ChatMessage("System", "ERROR: " + errorMessage, true));
            refreshChatDisplay();
        });
    }
}