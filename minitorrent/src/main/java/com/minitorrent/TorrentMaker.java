package com.minitorrent;

import com.github.cdefgah.bencoder4j.*;
import com.github.cdefgah.bencoder4j.model.BencodedByteSequence;
import com.github.cdefgah.bencoder4j.model.BencodedDictionary;
import com.github.cdefgah.bencoder4j.model.BencodedInteger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public class TorrentMaker {

    private static final int PIECE_LENGTH = 256 * 1024;

    public void createTorrent(TorrentMetadata metadata, String outputFileName) throws IOException, CircularReferenceException {
        BencodedDictionary root = new BencodedDictionary();
        root.put(new BencodedByteSequence("announce"), new BencodedByteSequence(metadata.getAnnounce()));

        TorrentInfo info = metadata.getInfo();
        BencodedDictionary infoDict = new BencodedDictionary();
        infoDict.put(new BencodedByteSequence("name"), new BencodedByteSequence(info.getName()));
        infoDict.put(new BencodedByteSequence("length"), new BencodedInteger(info.getLength()));
        infoDict.put(new BencodedByteSequence("piece length"), new BencodedInteger(info.getPieceLength()));

        // Concatenate SHA1 hashes into a single byte[]
        byte[] concatenatedHashes = concatenateHexStrings(info.getPieces());
        infoDict.put(new BencodedByteSequence("pieces"), new BencodedByteSequence(concatenatedHashes));

        root.put(new BencodedByteSequence("info"), infoDict);

        try (FileOutputStream fos = new FileOutputStream(outputFileName)) {
            root.writeObject(fos);
        }
    }

    public static TorrentMetadata createMetadata(File file, String announceURL) throws IOException, NoSuchAlgorithmException {
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        List<String> pieceHashes = computePieceHashes(fileBytes, PIECE_LENGTH);

        TorrentInfo info = new TorrentInfo(file.getName(), file.length(), PIECE_LENGTH, pieceHashes);

        return new TorrentMetadata(announceURL, info);
    }

    private static List<String> computePieceHashes(byte[] data, int pieceLength)
            throws NoSuchAlgorithmException {

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        List<String> hashes = new ArrayList<>();

        for (int i = 0; i < data.length; i += pieceLength) {
            int end = Math.min(i + pieceLength, data.length);
            byte[] piece = new byte[end - i];
            System.arraycopy(data, i, piece, 0, piece.length);
            byte[] hash = sha1.digest(piece);
            hashes.add(bytesToHex(hash));
        }

        return hashes;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] concatenateHexStrings(List<String> hexList) {
        byte[] result = new byte[hexList.size() * 20]; // 20 bytes per SHA-1 hash
        for (int i = 0; i < hexList.size(); i++) {
            byte[] hash = hexStringToBytes(hexList.get(i));
            System.arraycopy(hash, 0, result, i * 20, 20);
        }
        return result;
    }

    private static byte[] hexStringToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                  + Character.digit(s.charAt(i+1), 16));
        return data;
    }
}


