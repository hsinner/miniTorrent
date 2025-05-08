import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;

public class Threads extends Thread {
    String toConnectIP;
    int port;
    byte[] myID;
    byte[] infoHash;
    byte[] theirID;

    public Threads(String toConnectIP, int port, byte[] myID, byte[] infoHash) {
        this.toConnectIP = toConnectIP;
        this.port = port;
        this.myID = myID;
        this.infoHash = infoHash;
    }

    // Overriding the run method
    @Override
    public void run() {
        // will go to start then here
        Socket socket;

        try {
            socket = new Socket(toConnectIP, port);

            // for sending and recv
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // getting stuff for first message
            byte plen = 19;
            String prot = "BitTorrent protocol";
            byte[] res = new byte[8];

            ByteArrayOutputStream handShake = new ByteArrayOutputStream();
            handShake.write(plen);
            handShake.write(prot.getBytes("ISO-8859-1"));
            handShake.write(res);
            handShake.write(infoHash);
            handShake.write(myID);
            out.write(handShake.toByteArray());

            out.flush();

            byte[] response = new byte[68]; // same size as what we sent
            int read = 0;

            while (read < 68) { // reading response which is mirror of handshake
                int bytesR = in.read(response, read, 68 - read);
                if (bytesR == -1) {
                    System.out.println("Something went wrong");
                    return;
                }
                read += bytesR;
            }

            // check if protocol is the same
            int responseLen = response[0] & 0xFF;
            String responseProt = new String(response, 1, responseLen, "ISO-8859-1");
            if (!responseProt.equals(prot)) {
                System.out.println("Protocol recieved not BitTorrent protocol");
                return;
            }

            // check if hashes are exact match (so it's the same file)
            byte[] hashRecieved = Arrays.copyOfRange(response, 28, 48);
            if (!Arrays.equals(hashRecieved, infoHash)) {
                System.out.println("The info hashes did not match exactly");
                return;
            }

            // handshake was good
            System.out.println("Handshake was good, now get data");

        } catch (UnknownHostException e) {

            e.printStackTrace();
        } catch (IOException e) {

            e.printStackTrace();
        }

    }
}
