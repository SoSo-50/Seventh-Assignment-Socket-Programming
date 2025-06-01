package Client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.SocketException;

public class ClientReceiver implements Runnable {
    private BufferedReader in;

    public ClientReceiver(InputStream inputStream) {
        this.in = new BufferedReader(new InputStreamReader(inputStream));
    }

    @Override
    public void run() {
        try {
            String serverMessage;
            while (!Thread.currentThread().isInterrupted() && (serverMessage = in.readLine()) != null) {
                // Avoid printing if it's an echo of what the client just typed or a specific command response
                // that is handled synchronously. For general chat messages / notifications:
                System.out.println(serverMessage); // Display message from server
            }
        } catch (SocketException e) {
            if (!Thread.currentThread().isInterrupted()) { // If not intentionally interrupted
                System.out.println("\nConnection to server lost (SocketException in receiver). Press Enter to return to menu or exit.");
            }
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) {
                System.out.println("\nError receiving message from server (IOException in receiver): " + e.getMessage() + ". Press Enter.");
            }
        } finally {
             System.out.println("Client receiver thread stopping.");
            // Stream 'in' will be closed when the main client socket is closed.
        }
    }
}