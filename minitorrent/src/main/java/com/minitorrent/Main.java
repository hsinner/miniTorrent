package com.minitorrent;

import com.github.cdefgah.bencoder4j.CircularReferenceException;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;

public class Main {
    public static void main(String[] args) {
        File inputFile = new File("shrek.png"); 
        File outputFile = new File("shrek.torrent");

        String announceURL = "http://tracker.opentrackr.org:1337/announce";

        try {
            TorrentMetadata metadata = TorrentMaker.createMetadata(inputFile, announceURL);
            TorrentMaker maker = new TorrentMaker();
            maker.createTorrent(metadata, outputFile.getAbsolutePath());
            System.out.println("Torrent created: " + outputFile.getAbsolutePath());
        } catch (IOException | NoSuchAlgorithmException | CircularReferenceException e) {
            System.err.println("Error creating torrent: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
