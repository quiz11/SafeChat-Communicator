package com.safechat.server;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Scanner;


/**
 * Main entry point for the SafeChat server application.
 * <p>
 * This class orchestrates the lifecycle of the server component:
 * <ul>
 *   <li>Prompts for and validates the listening TCP port (default: 5000, valid range: 1–65535).</li>
 *   <li>Initializes the central connection registry ({@link ConnectionManager}) responsible
 *       for message routing and peer state tracking.</li>
 *   <li>Opens the server socket ({@link ServerSocket}) within a try-with-resources block.</li>
 *   <li>Runs an infinite loop accepting incoming client socket connections ({@link Socket}).</li>
 *   <li>Spawns a dedicated worker thread ({@link ClientHandler}) for each connected client.</li>
 * </ul>
 * <p>
 * In the SafeChat architecture, the server acts as a Zero-Knowledge relay broker.
 * It has no access to private keys or negotiated session keys.
 *
 */
public class ServerMain{
    /**
     * Entry point of the server application.
     * <p>
     * Interactively acquires the port number from standard input ({@link System#in}),
     * binds to the specified port, and continuously accepts client connections.
     *
     */
    public static void main(String[] args){
        Scanner scanner = new Scanner(System.in);
        System.out.println("=== Server is starting... ===");

        int port = 5000; // temporal base port;
        boolean portOk = false;

        while (!portOk){
            System.out.print("Select server port [1-65535, default: 5000]: ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                portOk = true;
            } else {
                try {
                    port = Integer.parseInt(input);
                    if (port >= 1 && port <= 65535) {
                        portOk = true;
                    } else {
                        System.out.println("Error: Port out of range");
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Error: Invalid port format");
                }
            }
        }

        System.out.println("Starting server on port " + port);
        ConnectionManager connectionManager = new ConnectionManager();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("The server is listening on port: " + port);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("New client, IP: " + clientSocket.getInetAddress());

                // new thread
                ClientHandler handler = new ClientHandler(clientSocket, connectionManager);
                Thread thread = new Thread(handler);
                thread.start();
            }

        } catch (IOException e) {
            System.err.println("Fatal Error: Could not listen on port " + port);
            System.err.println("Details: " + e.getMessage());
            e.printStackTrace();
        }
    }
}