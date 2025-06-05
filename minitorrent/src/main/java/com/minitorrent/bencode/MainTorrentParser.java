package com.minitorrent.bencode;

public class MainTorrentParser {
    public static void main(String[] args) {
        TorrentParser tp = new TorrentParser();
        try {
            tp.parseTorrentFile("bencode/sample.torrent");
            // C:\Users\wilhelk\bitTorrent_432\csse432BitTorrent\minitorrent\sample.torrent
            // minitorrent\sample.torrent
            // minitorrent\src\main\java\com\minitorrent\bencode\sample.torrent
        } catch (Exception e) {

            e.printStackTrace();
        }
        System.out.println("done");
    }
}
