package com.safechat.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Main JavaFX UI application class for the SafeChat client.
 * <p>
 * Responsible for loading the FXML view hierarchy ({@code /chat-view.fxml}), setting up the primary
 * window stage, establishing title and dimensions, and registering window closing event hooks.
 */
public class ClientGUI extends Application {

    /**
     * Initializes and displays the primary application stage with the loaded FXML scene.
     *
     * @param stage the primary window stage provided by the JavaFX runtime
     * @throws Exception if loading the FXML resource fails
     */
    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader fxmlLoader = new FXMLLoader(getClass().getResource("/chat-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 800, 600);

        stage.setTitle("SafeChat - E2E Encrypted Messenger");
        stage.setScene(scene);

        stage.setOnCloseRequest(e -> {
            Platform.exit();
            System.exit(0);
        });

        stage.show();
    }

    /**
     * Launches the JavaFX application lifecycle.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        launch(args);
    }
}