package com.minitorrent;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import com.dampcake.bencode.Bencode;
import com.dampcake.bencode.Type;
import com.minitorrent.TorrentMsg.MsgType;

public class bitTClient {
    // global access data
    private File outputFileGlobal;
    private long pieceLengthGlobal;
    private long fileLengthGlobal;
    private int numPiecesGlobal;

    private boolean done = false;

    // global access info bytes
    private byte[] infoHashGlobal;
    private byte[] peerIdGlobal;
    private byte[] piecesHashGlobal;

    // piece management
    private byte[][] pieceDataBuffers;
    private boolean[] pieceCompleted;
    private int[] pieceBlockTracker;
    private final Object pieceLock = new Object();
    private byte[] localBitfield = null;
    private static RandomAccessFile file = null;

    // private final List<PeerSession> activePeerSessions = new ArrayList<>();
    private final List<Thread> peerThreads = new ArrayList<>();

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private final Bencode bencode = new Bencode(StandardCharsets.ISO_8859_1);
    private static final int BLOCK_SIZE = 16 * 1024;

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: <torrent-file> <output-file>");
            return;
        }
        bitTClient client = new bitTClient();
        try {
            client.start(args[0], args[1]);
        } catch (IOException | NoSuchAlgorithmException e) {
            System.err.println("Client failed to start: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void start(String torrentFilePath, String outputFilePath) throws IOException, NoSuchAlgorithmException {
        System.out.println("Torrent file: " + torrentFilePath);
        System.out.println("Output file: " + outputFilePath);

        File torrentFile = new File(torrentFilePath);
        this.outputFileGlobal = new File(outputFilePath);

        if (!torrentFile.exists()) {
            System.err.println("Torrent file not found: " + torrentFilePath);
            return;
        }

        byte[] torrentBytes = Files.readAllBytes(torrentFile.toPath());

        if (!outputFileGlobal.exists()) {
            if (!outputFileGlobal.createNewFile()) {
                System.err.println("Could not create output file: " + outputFileGlobal.getAbsolutePath());
                return;
            }
        }

        Map<String, Object> torrentDict = bencode.decode(torrentBytes, Type.DICTIONARY);

        // extract announce URL
        String announceUrl = (String) torrentDict.get("announce");
        System.out.println("Announce URL: " + announceUrl);

        // extract info dictionary
        @SuppressWarnings("unchecked")
        Map<String, Object> infoDict = (Map<String, Object>) torrentDict.get("info");
        if (infoDict == null) {
            System.err.println("Info dictionary cannot be found in the torrent file");
            return;
        }

        // extract name
        String name = (String) infoDict.get("name");
        System.out.println("Torrent Name: " + name);

        // extract piece length
        this.pieceLengthGlobal = ((Long) infoDict.get("piece length")).longValue();
        System.out.println("Piece Length: " + pieceLengthGlobal);

        // extract file length
        this.fileLengthGlobal = ((Long) infoDict.get("length")).longValue();
        System.out.println("File Length: " + fileLengthGlobal);

        if (fileLengthGlobal == 0 || pieceLengthGlobal == 0) {
            System.err.println("You have nothing in your file gng");
            return;
        }

        // create new file with file length
        try (RandomAccessFile raf = new RandomAccessFile(outputFileGlobal, "rw")) {
            raf.setLength(fileLengthGlobal);
        } catch (IOException e) {
            System.err.println("Error with pre-allocation for " + outputFileGlobal);
        }

        this.numPiecesGlobal = (int) ((fileLengthGlobal + pieceLengthGlobal - 1) / pieceLengthGlobal);
        this.pieceDataBuffers = new byte[numPiecesGlobal][];
        this.pieceCompleted = new boolean[numPiecesGlobal];
        this.pieceBlockTracker = new int[numPiecesGlobal];
        System.out.println("Num pieces: " + numPiecesGlobal);

        String piecesObj = (String) infoDict.get("pieces");
        this.piecesHashGlobal = piecesObj.getBytes(StandardCharsets.ISO_8859_1);

        // compute SHA-1 info hash
        // get raw infodict in bytes
        // hash the raw bytes
        byte[] infoBytes = bencode.encode(infoDict);
        this.infoHashGlobal = MessageDigest.getInstance("SHA-1").digest(infoBytes);

        int bitfieldLength = (int) Math.ceil(numPiecesGlobal / 8.0);
        localBitfield = new byte[bitfieldLength];

        // creating peer ID
        byte[] peerId = new byte[20];
        this.peerIdGlobal = peerId;
        SecureRandom ran = new SecureRandom();
        ran.nextBytes(peerId);

        // tracker communication
        List<Peer> peers = null;

        int retryCount = 0;
        while (retryCount < 3) {
            try {
                peers = connectToTracker(announceUrl);
                break;
            } catch (SocketTimeoutException e) {
                retryCount++;
                System.err.println("Tracker timeout, retrying (" + retryCount + "/3)...");
            } catch (IOException e) {
                System.err.println("Tracker announce failed: " + e.getMessage());
                return;
            }
        }

        if (peers == null || peers.isEmpty()) {
            System.out.println("No peers found... bye bye");
            return;
        }
        System.out.println("Found " + peers.size() + " peers from tracker");

        // peer connection and communication
        int maxConcurrent = 20;
        int connectionAttemptCount = 0;

        // open connection for all
        for (Peer peer : peers) {
            System.out.println("Peer info: " + peer.getHost() + ":" + peer.getPort());
            try {
                // 1) Open the TCP connection with timeouts
                peer.connect();

                // Initialize bitfield before any message handling
                peer.initializeBitfield(numPiecesGlobal);

                // 2) Send our 68‑byte handshake
                peer.sendHandshake(infoHashGlobal, peerIdGlobal);

                // 3) Read & verify their handshake (throws if infoHash mismatches or other
                // issues)
                peer.receiveHandshake(infoHashGlobal);
                TorrentMsg sendingBitField = new TorrentMsg(TorrentMsg.MsgType.BITFIELD, this.localBitfield);
                sendMsg(peer, sendingBitField);

                // 4) send "interested" message
                // peer.sendInterested();
                sendMsg(peer, new TorrentMsg(TorrentMsg.MsgType.BITFIELD, this.localBitfield));
                peer.amChoking = false;
                TorrentMsg unchoke = new TorrentMsg(MsgType.UNCHOKE);
                sendMsg(peer, unchoke);
            } catch (SocketTimeoutException e) {
                System.err.println("TIMEOUT with " + peer.getHost() + ":" + peer.getPort() + " -> " + e.getMessage());
            } catch (EOFException e) {
                System.err.println("EOF (Connection closed by peer) with "
                        + peer.getHost() + ":" + peer.getPort() + " -> " + e.getMessage());
            } catch (IOException e) {
                System.err.println("I/O ERROR with " + peer.getHost() + ":" + peer.getPort() + " -> " +
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }

        }

        // pull msg from each
        while (!done) {
            for (Peer peer : peers) {
                try {
                    TorrentMsg receivedMsg = peer.readMessage();
                    if (receivedMsg == null) {
                        continue;
                    }

                    System.out.println("RECV from " + peer.getHost() + ":" + peer.getPort() + ": " + receivedMsg);
                    int pieceIndex = receivedMsg.getIndex();

                    switch (receivedMsg.getType()) {
                        case KEEP_ALIVE:
                            // Peer is keeping connection alive
                            System.out.println("Peer send KEEP_ALIVE");
                            break;
                        case CHOKE:
                            System.out.println("Peer is CHOKING us. Cannot request pieces yet.");
                            peer.peerChoking = true;
                            // We are choked by this peer
                            break;
                        case UNCHOKE:
                            System.out.println(
                                    "Peer is UNCHOKING us! We can request pieces now (if we have their bitfield).");
                            peer.peerChoking = false;
                            requestNextPieces(peer);
                            break;
                        case BITFIELD:
                            System.out.println("Peer sent BITFIELD. Length: "
                                    + (receivedMsg.getField() != null ? receivedMsg.getField().length : "N/A"));
                            // Store this peer's bitfield
                            peer.updatePeerBitfield(receivedMsg.getField());
                            sendMsg(peer, new TorrentMsg(MsgType.INTERESTED));
                            break;
                        case HAVE:
                            System.out.println("Peer HAS piece index: " + receivedMsg.getIndex());
                            peer.setPeerBitfield(pieceIndex);
                            if (!hasPiece(localBitfield, pieceIndex)) {
                                System.out.println("We want piece " + pieceIndex + ". Send INTERESTED");
                                peer.amInterested = true;
                                sendMsg(peer, new TorrentMsg(TorrentMsg.MsgType.INTERESTED));
                            } else if (isAllTrue(peer.remoteBitfield)) {
                                if (peer.amChoking == false) {
                                    peer.amChoking = true;
                                    sendMsg(peer, new TorrentMsg(TorrentMsg.MsgType.CHOKE));
                                }
                            }
                            break;
                        case PIECE:
                            handlePieceMessage(peer, receivedMsg);
                            // Request more pieces if this peer isn't busy
                            if (!peer.peerChoking && peer.peerInterested) {
                                requestNextPieces(peer);
                            }
                            break;
                        case INTERESTED:
                            peer.peerInterested = true;
                            break;
                        case UNINTERESTED:
                            peer.peerInterested = false;
                            break;
                        case REQUEST:
                            int beginOffset = receivedMsg.getBegin();
                            int blockLength = receivedMsg.getLength();

                            System.out.println("Peer is REQUESTING piece " + pieceIndex);

                            if (peer.amChoking) {
                                System.out.println("We are choking the peer, not sending the piece.");
                                break;
                            }

                            TorrentMsg reply;
                            if (!hasPiece(localBitfield, pieceIndex)) {
                                reply = new TorrentMsg(TorrentMsg.MsgType.BITFIELD, localBitfield);
                            } else {
                                byte[] replyData = new byte[blockLength];
                                try {
                                    file.seek(receivedMsg.getBegin());
                                    int bytesRead = file.read(replyData, 0, blockLength);
                                    if (bytesRead < blockLength) {
                                        System.out.println(
                                                "Warning: Expected to read " + blockLength + " bytes, but only read "
                                                        + bytesRead);
                                    }
                                } catch (IOException ex) {
                                    ex.printStackTrace();
                                }
                                reply = new TorrentMsg(TorrentMsg.MsgType.PIECE, pieceIndex, beginOffset, replyData);
                            }
                            sendMsg(peer, reply);
                            break;
                        case CANCEL:
                            break;
                        default:
                            break;
                    }

                    if (isAllTrue(localBitfield)) {
                        System.out.println("are we done?");
                        done = false;
                        break;
                    }

                    if (!peer.amInterested && peer.getPiecePeerHas(localBitfield) > -1) {
                        peer.amInterested = true;
                        TorrentMsg msg = new TorrentMsg((TorrentMsg.MsgType.INTERESTED));
                        sendMsg(peer, msg);
                    }

                    if (!done) // requesting pieces from non-choked peers
                    {
                        int i;
                        if (!peer.amChoking && peer.peerInterested // check it is not choked and is interested

                                && (i = peer.getPiecePeerHas(localBitfield)) > -1 // make sure peer has piece we don't
                                                                                  // and

                                && !peer.sentRequests.contains(i)) // check that we haven't asked for this index already

                        {

                            int iLen = (int) pieceLengthGlobal; // length of peice

                            if (i == numPiecesGlobal - 1 && fileLengthGlobal % pieceLengthGlobal > 0) // is last piece
                                                                                                      // and

                            {

                                iLen = (int) (fileLengthGlobal % pieceLengthGlobal);// set to the rest of the file

                                // since a file won't always be split evenly at the end

                            }

                            TorrentMsg req = new TorrentMsg(TorrentMsg.MsgType.REQUEST, i,
                                    (int) (i * pieceLengthGlobal), iLen);

                            peer.sentRequests.add(i); // adding to the list of pieces we've asked for

                            sendMsg(peer, req); // sending msg to peer

                        }

                    }

                } catch (EOFException e) {
                    System.out.println("Peer closed connection: " + peer.getHost() + ":" + peer.getPort());
                    peer.close();
                } catch (IOException e) {
                    System.err.println("I/O ERROR with " + peer.getHost() + ":" + peer.getPort() + " -> " +
                            e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                // Add a small delay if you want to see messages spaced out, not strictly
                // necessary
                // Thread.sleep(100);
            }
        }
        for (Peer peer : peers) {
            peer.close();
        }
        return;
    }

    private List<Peer> connectToTracker(String announceUrl) throws IOException {
        int portURL = 6881;
        long uploaded = 0;
        long downloaded = 0;
        long left = fileLengthGlobal;
        String event = "started";

        String encodedInfoHash = percentEncode(infoHashGlobal);
        String encodedPeerID = percentEncode(peerIdGlobal);

        String trackerUrlString = String.format(
                "%s%sinfo_hash=%s&peer_id=%s&port=%d&uploaded=%d&downloaded=%d&left=%d&compact=1&event=%s",
                announceUrl,
                (announceUrl.contains("?") ? "&" : "?"),
                encodedInfoHash,
                encodedPeerID,
                portURL,
                uploaded,
                downloaded,
                left,
                event);

        System.out.println("Announcing to tracker:\n" + trackerUrlString);
        URL url = new URL(trackerUrlString);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        con.setConnectTimeout(CONNECT_TIMEOUT_MS);
        con.setReadTimeout(READ_TIMEOUT_MS);

        List<Peer> peerList = new ArrayList<>();
        int responseCode = con.getResponseCode();

        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (InputStream responseStream = con.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] data = new byte[1024];
                int bytesRead;
                while ((bytesRead = responseStream.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, bytesRead);
                }
                byte[] responseBytes = buffer.toByteArray();
                Map<String, Object> trackerResponse = bencode.decode(responseBytes, Type.DICTIONARY);
                System.out.println("Tracker response: " + trackerResponse);

                if (trackerResponse.containsKey("failure reason")) {
                    System.err.println("Tracker failure: " + trackerResponse.get("failure reason"));
                    return peerList;
                }

                String peersObj = (String) trackerResponse.get("peers");
                peerParse(peersObj, peerList);
            }
        } else {
            System.err.println("Tracker request failed. HTTP Error Code: " + responseCode);
        }
        con.disconnect();
        return peerList;
    }

    private void peerParse(String peersObj, List<Peer> peerList) {
        byte[] peers = peersObj.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < peers.length; i += 6) {
            String ip = String.format("%d.%d.%d.%d",
                    peers[i] & 0xff,
                    peers[i + 1] & 0xff,
                    peers[i + 2] & 0xff,
                    peers[i + 3] & 0xff);
            int port = (((peers[i + 4] & 0xff) << 8) | peers[i + 5] & 0xff);
            if (port > 0) {
                peerList.add(new Peer(ip, port));
            }
        }
        if (peersObj.isEmpty()) {
            System.err.println("Peers data from tracker ain't real " + peersObj.getClass().getName());
        }
    }

    private void verifyAndSavePiece(int pieceIndex) throws IOException, NoSuchAlgorithmException {
        byte[] pieceData = pieceDataBuffers[pieceIndex];
        byte[] actualHash = MessageDigest.getInstance("SHA-1").digest(pieceData);

        // Get expected hash from torrent file
        byte[] expectedHash = new byte[20];
        System.arraycopy(piecesHashGlobal, pieceIndex * 20, expectedHash, 0, 20);

        if (Arrays.equals(actualHash, expectedHash)) {
            System.out.println("   Piece " + pieceIndex + " completed and verified!");
            pieceCompleted[pieceIndex] = true;

            // Write piece to file
            try (RandomAccessFile file = new RandomAccessFile(outputFileGlobal, "rw")) {
                file.seek(pieceIndex * pieceLengthGlobal);
                file.write(pieceData);
            }
            System.out.println("   Successfully saved piece " + pieceIndex + " to disk");
        } else {
            System.out.println("   Piece " + pieceIndex + " verification failed!");
        }
    }

    // the peers need to be bittorrent encoded, which is raw percent encoding...
    private static String percentEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%%%02X", b));
        }
        return sb.toString();
    }

    // this goes at bottom of bitclient
    public static void sendMsg(Peer peer, TorrentMsg msg) {
        byte[] packedMsg = msg.turnIntoBytes();
        try {
            peer.sendMessage(packedMsg);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public static boolean isAllTrue(byte[] bitfield) {
        for (byte b : bitfield) {
            if (b != (byte) 0xFF) {
                return false;
            }
        }
        return true;
    }

    public static boolean hasPiece(byte[] bitfield, int pieceIndex) {
        if (pieceIndex < 0)
            return false; // invalid index

        int byteIndex = pieceIndex / 8;
        int bitIndex = 7 - (pieceIndex % 8); // BitTorrent uses MSB first

        if (byteIndex >= bitfield.length)
            return false; // out of bounds

        return (bitfield[byteIndex] & (1 << bitIndex)) != 0;
    }

    public static void setPiece(byte[] bitfield, int pieceIndex) {
        if (pieceIndex < 0)
            return; // Invalid input

        int byteIndex = pieceIndex / 8;
        int bitIndex = 7 - (pieceIndex % 8); // BitTorrent is MSB-first

        if (byteIndex >= bitfield.length)
            return; // Out of bounds

        bitfield[byteIndex] |= (1 << bitIndex);
    }

    public int getNumPiecesGlobal() {
        return numPiecesGlobal;
    }

    private void requestFirstPiece(Peer peer) throws IOException {
        int pieceIndex = 0;
        int numBlocks = (int) Math.ceil((double) pieceLengthGlobal / BLOCK_SIZE);

        for (int block = 0; block < numBlocks; block++) {
            int begin = block * BLOCK_SIZE;
            int length = (block == numBlocks - 1)
                    ? (int) (pieceLengthGlobal - (block * BLOCK_SIZE))
                    : BLOCK_SIZE;

            TorrentMsg requestMsg = new TorrentMsg(TorrentMsg.MsgType.REQUEST,
                    pieceIndex, begin, length);
            sendMsg(peer, requestMsg);
            System.out.println("   Sent REQUEST for piece " + pieceIndex +
                    ", offset " + begin + ", length " + length);
        }
    }

    private void handlePieceMessage(Peer peer, TorrentMsg msg) throws IOException, NoSuchAlgorithmException {
        System.out.println("   Received PIECE message. Index: " + msg.getIndex() +
                ", Begin: " + msg.getBegin() +
                ", Length: " + msg.getChunk().length);

        int pieceIndex = msg.getIndex();
        int begin = msg.getBegin();
        byte[] data = msg.getChunk();

        synchronized (pieceLock) {
            // Initialize buffer if needed
            if (pieceDataBuffers[pieceIndex] == null) {
                int thisPieceLength = pieceIndex == numPiecesGlobal - 1
                        ? (int) (fileLengthGlobal - (pieceIndex * pieceLengthGlobal))
                        : (int) pieceLengthGlobal;
                pieceDataBuffers[pieceIndex] = new byte[thisPieceLength];
            }

            // Store block data
            System.arraycopy(data, 0, pieceDataBuffers[pieceIndex], begin, data.length);
            pieceBlockTracker[pieceIndex]++;

            // Check if piece is complete
            int expectedBlocks = (int) Math.ceil((double) pieceDataBuffers[pieceIndex].length / BLOCK_SIZE);

            if (pieceBlockTracker[pieceIndex] == expectedBlocks) {
                verifyAndSavePiece(pieceIndex);
                if (pieceCompleted[pieceIndex]) {
                    // Piece verified and saved successfully
                    peer.sentRequests.remove(Integer.valueOf(pieceIndex));
                    setPiece(localBitfield, pieceIndex);

                    // Request next piece if we haven't finished
                    if (!isAllPiecesComplete()) {
                        requestNextPieces(peer);
                    } else {
                        done = true;
                    }
                } else {
                    // Verification failed - reset and retry
                    System.out.println("   Piece " + pieceIndex + " verification failed - requesting again");
                    pieceDataBuffers[pieceIndex] = null;
                    pieceBlockTracker[pieceIndex] = 0;
                    requestPiece(peer, pieceIndex);
                }
            }
        }
    }

    private boolean isAllPiecesComplete() {
        for (boolean completed : pieceCompleted) {
            if (!completed)
                return false;
        }
        return true;
    }

    private void requestNextPieces(Peer peer) throws IOException {
        // Don't request if we're being choked
        if (peer.peerChoking) {
            return;
        }

        // Find next piece we need that this peer has
        int nextPiece = findNextNeededPiece();
        while (nextPiece != -1 && !peer.hasPiece(nextPiece)) {
            nextPiece = findNextNeededPiece();
        }

        if (nextPiece != -1) {
            requestPiece(peer, nextPiece);
            peer.sentRequests.add(nextPiece);
        }
    }

    private int findNextNeededPiece() {
        for (int i = 0; i < pieceCompleted.length; i++) {
            if (!pieceCompleted[i])
                return i;
        }
        return -1;
    }

    private void requestPiece(Peer peer, int pieceIndex) throws IOException {
        int thisPieceLength = pieceIndex == numPiecesGlobal - 1
                ? (int) (fileLengthGlobal - (pieceIndex * pieceLengthGlobal))
                : (int) pieceLengthGlobal;

        int numBlocks = (int) Math.ceil((double) thisPieceLength / BLOCK_SIZE);

        for (int block = 0; block < numBlocks; block++) {
            int begin = block * BLOCK_SIZE;
            int length = (block == numBlocks - 1)
                    ? (thisPieceLength - (block * BLOCK_SIZE))
                    : BLOCK_SIZE;

            TorrentMsg requestMsg = new TorrentMsg(TorrentMsg.MsgType.REQUEST,
                    pieceIndex, begin, length);
            sendMsg(peer, requestMsg);
            System.out.println("Requested piece " + pieceIndex +
                    ", block " + block + "/" + numBlocks);
        }
    }

}
