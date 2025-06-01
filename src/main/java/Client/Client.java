package Client;

import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Scanner;


public class Client {
    private static Socket socket; // Made static to be accessible by helper methods
    private static PrintWriter out;
    private static BufferedReader in;
    private static DataOutputStream dataOut;
    private static DataInputStream dataIn;
    private static String username; // Stores the username after successful login
    private static ClientReceiver clientReceiver; // To manage the receiver thread
    private static Thread receiverThread;


    public static void main(String[] args) {
        String hostname = "localhost";
        int port = 12345;

        try {
            socket = new Socket(hostname, port); // Initialize the static socket
            // Initialize streams after socket connection
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            // dataOut and dataIn will be initialized when needed for file transfer
            dataOut = new DataOutputStream(socket.getOutputStream()); // Initialize here
            dataIn = new DataInputStream(socket.getInputStream());   // Initialize here


            System.out.println("Connected to server: " + hostname + ":" + port);
            Scanner scanner = new Scanner(System.in);

            // --- LOGIN PHASE ---
            System.out.println("===== Welcome to CS Music Room =====");

            boolean loggedIn = false;
            while (!loggedIn) {
                System.out.print("Username: ");
                String currentUsernameAttempt = scanner.nextLine();
                System.out.print("Password: ");
                String password = scanner.nextLine();

                sendLoginRequest(currentUsernameAttempt, password);

                try {
                    String serverResponse = in.readLine();
                    if (serverResponse == null) {
                        System.out.println("Server closed connection during login. Exiting.");
                        return;
                    }
                    if ("LOGIN_SUCCESS".equals(serverResponse)) {
                        loggedIn = true;
                        Client.username = currentUsernameAttempt; // Store username
                        System.out.println("Login successful! Welcome " + Client.username);

                        // Start ClientReceiver thread after successful login
                        clientReceiver = new ClientReceiver(socket.getInputStream());
                        receiverThread = new Thread(clientReceiver);
                        receiverThread.start();

                    } else {
                        System.out.println("Login failed: " + serverResponse);
                        System.out.println("Please try again.");
                    }
                } catch (IOException e) {
                    System.out.println("Error receiving login response: " + e.getMessage() + ". Exiting.");
                    return; // Exit if error during login response
                }
            }

            // --- ACTION MENU LOOP ---
            while (loggedIn && !socket.isClosed()) { // Check if socket is still open
                printMenu();
                System.out.print("Enter choice: ");
                String choice = scanner.nextLine();

                try {
                    switch (choice) {
                        case "1" -> enterChat(scanner);
                        case "2" -> uploadFile(scanner);
                        case "3" -> requestDownload(scanner);
                        case "0" -> {
                            System.out.println("Exiting...");
                            loggedIn = false; // To break the loop
                            // No need to return here; let finally block handle cleanup
                        }
                        default -> System.out.println("Invalid choice.");
                    }
                    if (!loggedIn) break; // Break if user chose to exit
                } catch (IOException e) {
                    System.out.println("An error occurred: " + e.getMessage());
                    System.out.println("You might have been disconnected from the server.");
                    loggedIn = false; // Assume disconnected
                }
            }

        } catch (UnknownHostException e) {
            System.err.println("Don't know about host " + hostname);
        } catch (IOException e) {
            System.err.println("Couldn't get I/O for the connection to " + hostname + ". Is server running?");
            // e.printStackTrace();
        } finally {
            System.out.println("Closing connection and resources...");
            try {
                if (out != null) out.println("/disconnect"); // Polite disconnect message
                if (receiverThread != null && receiverThread.isAlive()) {
                    receiverThread.interrupt(); // Signal receiver thread to stop
                }
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                System.err.println("Error closing socket: " + e.getMessage());
            }
            System.out.println("Client shut down.");
        }
    }

    private static void printMenu() {
        System.out.println("\n--- Main Menu (" + Client.username + ") ---");
        System.out.println("1. Enter chat box");
        System.out.println("2. Upload a file");
        System.out.println("3. Download a file");
        System.out.println("0. Exit");
    }

    private static void sendLoginRequest(String username, String password) {
        out.println("LOGIN|" + username + "|" + password);
    }

    private static void enterChat(Scanner scanner) throws IOException {
        System.out.println("You have entered the chat. Type '/exit' to leave this chat session.");
        // ClientReceiver is already started after login.
        // New messages from server will be printed by ClientReceiver thread.

        String messageString;
        while (true) {
            messageString = scanner.nextLine(); // Read user input for chat
            if (messageString.equalsIgnoreCase("/exit")) {
                // out.println("/EXIT_CHAT"); // Optional: specific command to server
                System.out.println("Exiting chat session, back to main menu.");
                break; // Exit chat loop, back to main menu
            }
            if (!messageString.isEmpty()) {
                sendChatMessage(messageString);
            }
        }
    }

    private static void sendChatMessage(String messageToSend) throws IOException {
        if (Client.username == null) {
            System.out.println("Error: Not logged in. Cannot send chat message.");
            return;
        }
        out.println("CHAT_MSG|" + Client.username + "|" + messageToSend);
    }

    private static void uploadFile(Scanner scanner) throws IOException {
        if (Client.username == null) {
            System.out.println("Error: Not logged in. Cannot determine user folder for upload.");
            return;
        }
        File clientDir = new File("resources/Client/" + Client.username);
        if (!clientDir.exists()) {
            if (!clientDir.mkdirs()) {
                System.out.println("Could not create client directory: " + clientDir.getPath());
                return;
            }
        }
        File[] files = clientDir.listFiles(File::isFile);

        if (files == null || files.length == 0) {
            System.out.println("No files to upload in " + clientDir.getPath());
            // Create a dummy file for testing if needed
            // File dummy = new File(clientDir, "sample.txt");
            // try (PrintWriter writer = new PrintWriter(dummy)) { writer.println("Hello World"); }
            // files = clientDir.listFiles(File::isFile);
            // if (files == null || files.length == 0) return;
            return;
        }

        System.out.println("Select a file to upload from " + clientDir.getAbsolutePath() + ":");
        for (int i = 0; i < files.length; i++) {
            System.out.println((i + 1) + ". " + files[i].getName() + " (" + files[i].length() + " bytes)");
        }
        System.out.print("Enter file number (or 0 to cancel): ");
        int choiceNum;
        try {
            choiceNum = Integer.parseInt(scanner.nextLine());
        } catch (NumberFormatException e) {
            System.out.println("Invalid input. Please enter a number.");
            return;
        }

        if (choiceNum == 0) {
            System.out.println("Upload cancelled.");
            return;
        }
        if (choiceNum < 1 || choiceNum > files.length) {
            System.out.println("Invalid choice.");
            return;
        }
        File selectedFile = files[choiceNum - 1];

        // Notify server: UPLOAD_REQUEST|filename|filesize
        out.println("UPLOAD_REQUEST|" + selectedFile.getName() + "|" + selectedFile.length());

        // Optional: Wait for server acknowledgment (e.g., "UPLOAD_READY")
        // String serverAck = in.readLine();
        // if (!"UPLOAD_READY".equals(serverAck)) {
        //     System.out.println("Server not ready for upload: " + (serverAck != null ? serverAck : "No response"));
        //     return;
        // }

        System.out.println("Uploading " + selectedFile.getName() + "...");
        FileInputStream fis = new FileInputStream(selectedFile);
        byte[] buffer = new byte[4096];
        int bytesRead;
        long totalBytesSent = 0;
        while ((bytesRead = fis.read(buffer)) != -1) {
            dataOut.write(buffer, 0, bytesRead);
            totalBytesSent += bytesRead;
        }
        dataOut.flush();
        fis.close();
        System.out.println("File " + selectedFile.getName() + " (" + totalBytesSent + " bytes) sent.");

        // Wait for server confirmation
        String uploadConfirm = in.readLine(); // e.g., "UPLOAD_SUCCESS|filename uploaded."
        System.out.println("Server: " + uploadConfirm);
    }

    private static void requestDownload(Scanner scanner) throws IOException {
        if (Client.username == null) {
            System.out.println("Error: Not logged in.");
            return;
        }
        out.println("LIST_FILES"); // Ask server for list of files

        String fileListStr = in.readLine();
        if (fileListStr == null || fileListStr.isEmpty() || "NO_FILES".equals(fileListStr)) {
            System.out.println("No files available for download from the server.");
            return;
        }

        String[] serverFiles = fileListStr.split(",");
        System.out.println("\nAvailable files on server for download:");
        for (int i = 0; i < serverFiles.length; i++) {
            System.out.println((i + 1) + ". " + serverFiles[i]);
        }

        System.out.print("Enter file number to download (or 0 to cancel): ");
        int fileNumChoice;
        try {
            fileNumChoice = Integer.parseInt(scanner.nextLine());
        } catch (NumberFormatException e) {
            System.out.println("Invalid input. Please enter a number.");
            return;
        }

        if (fileNumChoice == 0) {
            System.out.println("Download cancelled.");
            return;
        }
        if (fileNumChoice < 1 || fileNumChoice > serverFiles.length) {
            System.out.println("Invalid file number choice.");
            return;
        }
        String selectedFileToDownload = serverFiles[fileNumChoice - 1];

        out.println("DOWNLOAD_REQUEST|" + selectedFileToDownload);

        long fileSize = dataIn.readLong(); // 1. Read file size from server

        if (fileSize == -1) { // Convention for file not found by server
            System.out.println("Server reported file not found or error for: " + selectedFileToDownload);
            return;
        }
        if (fileSize == 0) {
            System.out.println("Server indicated file is empty (or error): " + selectedFileToDownload);
            // Potentially create an empty file or handle as error.
            // For now, just notify and return.
            return;
        }


        File userDownloadDir = new File("resources/Client/" + Client.username);
        if (!userDownloadDir.exists()) {
            if (!userDownloadDir.mkdirs()) {
                System.out.println("Could not create download directory: " + userDownloadDir.getPath());
                return;
            }
        }
        File outputFile = new File(userDownloadDir, selectedFileToDownload);
        FileOutputStream fos = new FileOutputStream(outputFile);

        System.out.println("Downloading " + selectedFileToDownload + " (" + fileSize + " bytes)...");
        byte[] buffer = new byte[4096];
        int bytesReadCount;
        long totalBytesRead = 0;

        while (totalBytesRead < fileSize && (bytesReadCount = dataIn.read(buffer, 0, (int) Math.min(buffer.length, fileSize - totalBytesRead))) != -1) {
            fos.write(buffer, 0, bytesReadCount);
            totalBytesRead += bytesReadCount;
        }
        fos.close();

        if (totalBytesRead == fileSize) {
            System.out.println("File " + selectedFileToDownload + " downloaded successfully to " + outputFile.getPath());
        } else {
            System.out.println("File download may be incomplete. Expected " + fileSize + " bytes, received " + totalBytesRead + " bytes.");
            // outputFile.delete(); // Optionally delete incomplete file
        }
    }
}