package Server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import Shared.User;

public class Server {
    // Predefined users for authentication
    private static final User[] users = {
            new User("user1", "1234"),
            new User("user2", "1234"),
            new User("user3", "1234"),
            new User("user4", "1234"),
            new User("user5", "1234"),
    };

    // List of currently connected clients - needs to be thread-safe
    public static List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) {
        int port = 12345; // Define the port number
        ServerSocket serverSocket = null;

        System.out.println("Starting server on port " + port + "...");

        try {
            serverSocket = new ServerSocket(port);
            System.out.println("Server started successfully on port " + port);

            while (true) { // Loop to continuously accept client connections
                Socket clientSocket = serverSocket.accept(); // Wait for a client to connect
                System.out.println("New client connected: " + clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort());

                ClientHandler clientHandler = new ClientHandler(clientSocket, clients);
                // No need to add to 'clients' here, ClientHandler's constructor or run method can do it
                // if login is successful. Or add now and remove if login fails.
                // For simplicity, let's add and let ClientHandler remove on failure/disconnect.
                clients.add(clientHandler); // Added here, ClientHandler will remove itself if needed.

                Thread handlerThread = new Thread(clientHandler);
                handlerThread.start();
                System.out.println("Total connected (pending login/active): " + clients.size());
            }

        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                    System.out.println("Server socket closed.");
                } catch (IOException e) {
                    System.err.println("Error closing server socket: " + e.getMessage());
                }
            }
        }
    }

    public static boolean authenticate(String username, String password) {
        for (User user : users) {
            if (user.getUsername().equals(username) && user.getPassword().equals(password)) {
                return true;
            }
        }
        return false;
    }
}