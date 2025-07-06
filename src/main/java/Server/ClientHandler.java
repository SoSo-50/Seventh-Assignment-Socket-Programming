package Server;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException; // For Scanner issues if used directly on socket stream
import java.util.StringTokenizer; // Alternative to split

public class ClientHandler implements Runnable {
    private Socket socket;
    private PrintWriter out;        // For sending text to the client
    private BufferedReader in;      // For receiving text from the client
    private DataOutputStream dataOut; // For sending binary data (files) to the client
    private DataInputStream dataIn;   // For receiving binary data (files) from the client
    private List<ClientHandler> allClients; // Shared list of all client handlers
    private String username; // Username of the connected client

    public ClientHandler(Socket socket, List<ClientHandler> allClients) {
        this.socket = socket;
        this.allClients = allClients; // The shared list
        try {
            this.out = new PrintWriter(socket.getOutputStream(), true);
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.dataOut = new DataOutputStream(socket.getOutputStream());
            this.dataIn = new DataInputStream(socket.getInputStream());
        } catch (IOException e) {
            System.err.println("ClientHandler Error: Could not initialize I/O streams for " + socket.getInetAddress() + ": " + e.getMessage());
            closeResources();
        }
    }

    @Override
    public void run() {
        try {
            // --- LOGIN PHASE ---
            String loginLine = in.readLine();
            if (loginLine == null) {
                System.out.println("Client " + socket.getInetAddress() + " disconnected before login.");
                return;
            }

            String[] loginParts = loginLine.split("\\|");
            if (loginParts.length == 3 && "LOGIN".equals(loginParts[0])) {
                handleLogin(loginParts[1], loginParts[2]);
            } else {
                sendMessage("LOGIN_FAIL|Invalid login request format."); // Use sendMessage to ensure 'out' is used
                System.out.println("Invalid login attempt from " + socket.getInetAddress());
                return;
            }

            if (this.username == null) { // Login failed
                System.out.println("Login procedure failed for " + socket.getInetAddress() + ". Closing connection.");
                // sendMessage was already called in handleLogin for failure cases
                return;
            }

            // --- MESSAGE PROCESSING LOOP (after successful login) ---
            String clientRequest;
            while ((clientRequest = in.readLine()) != null) {
                System.out.println("Received from [" + this.username + "]: " + clientRequest);
                String[] parts = clientRequest.split("\\|", 3);
                String command = parts[0].toUpperCase();

                switch (command) {
                    case "CHAT_MSG":
                        if (parts.length == 3) {
                            broadcast("[" + this.username + "]: " + parts[2]);
                        } else {
                            sendMessage("SERVER_MSG|Invalid chat message format.");
                        }
                        break;
                    case "UPLOAD_REQUEST":
                        if (parts.length == 3) {
                            String fileName = parts[1];
                            try {
                                long fileLength = Long.parseLong(parts[2]);
                                receiveFile(fileName, fileLength);
                            } catch (NumberFormatException e) {
                                sendMessage("SERVER_MSG|Invalid file length format for upload.");
                            }
                        } else {
                            sendMessage("SERVER_MSG|Invalid upload request format.");
                        }
                        break;
                    case "LIST_FILES":
                        sendFileList();
                        break;
                    case "DOWNLOAD_REQUEST":
                        if (parts.length == 2) {
                            String fileNameToDownload = parts[1];
                            sendFile(fileNameToDownload);
                        } else {
                            sendMessage("SERVER_MSG|Invalid download request format.");
                        }
                        break;
                    case "/EXIT_CHAT":
                        System.out.println("User " + this.username + " indicated leaving chat mode.");
                        break;
                    default:
                        sendMessage("SERVER_MSG|Unknown command: " + command);
                }
            }
        } catch (IOException e) {
            if (this.username != null) {
                System.out.println("Connection lost with client " + this.username + ": " + e.getMessage());
            } else {
                System.out.println("Connection lost with client " + socket.getInetAddress() + " (not logged in or login failed): " + e.getMessage());
            }
        } catch (ClassNotFoundException e) { // From handleLogin if it were to use ObjectInputStream
            System.err.println("ClientHandler Error: ClassNotFoundException for " + (this.username != null ? this.username : socket.getInetAddress()) + ": " + e.getMessage());
        } finally {
            // --- CLEANUP ---
            if (this.username != null) {
                System.out.println(this.username + " has disconnected.");
                try {
                    broadcast("SERVER_MSG|" + this.username + " has left the chat.");
                } catch (IOException e) {
                    System.err.println("Error broadcasting departure message: " + e.getMessage());
                }
            }
            // synchronized (allClients) { // Server.clients is already a synchronizedList
            allClients.remove(this);
            // }
            System.out.println("Client removed. Total active clients: " + allClients.size());
            closeResources();
        }
    }

    private void handleLogin(String username, String password) throws IOException, ClassNotFoundException {
        if (Server.authenticate(username, password)) {
            boolean alreadyConnected = false;
            // synchronized (allClients) { // Server.clients is already a synchronizedList
            for (ClientHandler handler : allClients) {
                // Check if another client handler (not this one) already has this username.
                // This instance might have been added to allClients before login is confirmed.
                if (handler != this && username.equals(handler.username)) {
                    alreadyConnected = true;
                    break;
                }
            }
            // }

            if (alreadyConnected) {
                this.username = null;
                sendMessage("LOGIN_FAIL|Username already logged in.");
                System.out.println("Login failed for " + username + " from " + socket.getInetAddress() + ": Already logged in.");
            } else {
                this.username = username;
                sendMessage("LOGIN_SUCCESS");
                System.out.println("User '" + this.username + "' logged in successfully from " + socket.getInetAddress());
                broadcast("SERVER_MSG|" + this.username + " has joined the chat.");
            }
        } else {
            this.username = null;
            sendMessage("LOGIN_FAIL|Invalid username or password.");
            System.out.println("Login failed for " + username + " from " + socket.getInetAddress() + ": Invalid credentials.");
        }
    }

    private void sendMessage(String msg) {
        if (out != null && !socket.isClosed() && !out.checkError()) {
            out.println(msg);
        } else {
            System.err.println("Cannot send message to " + (this.username != null ? this.username : socket.getInetAddress()) + ": Output stream or socket closed/error.");
        }
    }

    private void broadcast(String msg) throws IOException {
        // synchronized (allClients) { // Server.clients is already a synchronizedList
        // Create a temporary list for iteration to avoid ConcurrentModificationException
        // if a client disconnects (and is removed from allClients) during the broadcast.
        List<ClientHandler> currentClients = new ArrayList<>(allClients);
        System.out.println("Broadcasting: " + msg + " to " + currentClients.size() + " clients.");
        for (ClientHandler client : currentClients) {
            if (client.username != null && client != this) { // Send to other logged-in clients
                client.sendMessage(msg);
            } else if (client.username != null && client == this && msg.startsWith("[" + this.username + "]:")) {
                // Echo own chat message back to self
                client.sendMessage(msg);
            } else if (client.username != null && msg.startsWith("SERVER_MSG|")) {
                // Send server messages to all, including self if appropriate (e.g. user join/left)
                client.sendMessage(msg);
            }
        }
        // }
    }


    private void sendFileList() {
        File serverDir = new File("resources/Server/");
        if (!serverDir.exists() || !serverDir.isDirectory()) {
            System.err.println("Server resource directory not found: " + serverDir.getPath());
            sendMessage("NO_FILES");
            return;
        }
        File[] filesArray = serverDir.listFiles(File::isFile);
        if (filesArray == null || filesArray.length == 0) {
            sendMessage("NO_FILES");
            System.out.println("No files in server directory to list for " + this.username);
            return;
        }
        StringBuilder fileListBuilder = new StringBuilder();
        for (int i = 0; i < filesArray.length; i++) {
            fileListBuilder.append(filesArray[i].getName());
            if (i < filesArray.length - 1) {
                fileListBuilder.append(",");
            }
        }
        sendMessage(fileListBuilder.toString());
        System.out.println("Sent file list to " + this.username + ": " + fileListBuilder.toString());
    }

    private void sendFile(String fileName) {
        try {
            File fileToSend = new File("resources/Server/", new File(fileName).getName()); // Basic sanitization
            if (!fileToSend.exists() || !fileToSend.isFile()) {
                System.err.println("File not found on server: " + fileName + " (requested by " + this.username + ")");
                this.dataOut.writeLong(-1L); // Signal file not found
                this.dataOut.flush();
                return;
            }
            long fileSize = fileToSend.length();
            System.out.println("Sending file '" + fileName + "' (" + fileSize + " bytes) to " + this.username);
            this.dataOut.writeLong(fileSize);
            FileInputStream fis = new FileInputStream(fileToSend);
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                this.dataOut.write(buffer, 0, bytesRead);
            }
            this.dataOut.flush();
            fis.close();
            System.out.println("File '" + fileName + "' sent successfully to " + this.username);
        } catch (IOException e) {
            System.err.println("IOException sending file '" + fileName + "' to " + this.username + ": " + e.getMessage());
        }
    }

    private void receiveFile(String filename, long fileLength) {
        String sanitizedFilename = new File(filename).getName(); // Basic sanitization
        if (fileLength <= 0) {
            System.err.println("Invalid file length (" + fileLength + ") for: " + sanitizedFilename + " from " + this.username);
            sendMessage("UPLOAD_FAIL|Invalid file length.");
            return;
        }
        System.out.println("Receiving file: '" + sanitizedFilename + "' (" + fileLength + " bytes) from " + this.username);
        try {
            if (fileLength > Integer.MAX_VALUE) {
                System.err.println("File '" + sanitizedFilename + "' is too large.");
                sendMessage("UPLOAD_FAIL|File is too large.");
                // Drain the stream if client is still sending
                long bytesToSkip = fileLength;
                while (bytesToSkip > 0) {
                    long skipped = dataIn.skipBytes((int) Math.min(Integer.MAX_VALUE, bytesToSkip));
                    if (skipped == 0) break; // No more bytes to skip or stream error
                    bytesToSkip -= skipped;
                }
                return;
            }
            byte[] fileData = new byte[(int) fileLength];
            this.dataIn.readFully(fileData, 0, (int) fileLength);
            saveUploadedFile(sanitizedFilename, fileData);
            System.out.println("File '" + sanitizedFilename + "' received successfully from " + this.username);
            sendMessage("UPLOAD_SUCCESS|" + sanitizedFilename + " uploaded successfully.");
        } catch (IOException e) {
            System.err.println("IOException receiving file '" + sanitizedFilename + "' from " + this.username + ": " + e.getMessage());
            sendMessage("UPLOAD_FAIL|Error during file upload: " + e.getMessage());
        }
    }

    private void saveUploadedFile(String filename, byte[] data) throws IOException {
        File serverResourcesDir = new File("resources/Server/");
        if (!serverResourcesDir.exists()) {
            if (!serverResourcesDir.mkdirs()) {
                throw new IOException("Could not create server resources directory: " + serverResourcesDir.getPath());
            }
        }
        File outputFile = new File(serverResourcesDir, filename); // filename already sanitized
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            fos.write(data);
        }
        System.out.println("Saved uploaded file to: " + outputFile.getAbsolutePath());
    }

    private void closeResources() {
        try {
            if (out != null) out.close();
            if (in != null) in.close();
            if (dataOut != null) dataOut.close();
            if (dataIn != null) dataIn.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ex) {
            System.err.println("Error closing resources for client handler: " + ex.getMessage());
        }
    }
}