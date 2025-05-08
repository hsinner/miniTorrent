package com.minitorrent;

import java.util.List;

public class TorrentMetadata {
    private String announce;
    private TorrentInfo info;

    public TorrentMetadata(String announce, TorrentInfo info) {
        this.announce = announce;
        this.info = info;
    }

    public String getAnnounce() {
        return announce;
    }

    public void setAnnounce(String announce) {
        this.announce = announce;
    }

    public TorrentInfo getInfo() {
        return info;
    }

    public void setInfo(TorrentInfo info) {
        this.info = info;
    }
}

class TorrentInfo {
    private String name;
    private long length;
    private int pieceLength;
    private List<String> pieces;

    public TorrentInfo(String name, long length, int pieceLength, List<String> pieces) {
        this.name = name;
        this.length = length;
        this.pieceLength = pieceLength;
        this.pieces = pieces;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getLength() {
        return length;
    }

    public void setLength(long length) {
        this.length = length;
    }

    public int getPieceLength() {
        return pieceLength;
    }

    public void setPieceLength(int pieceLength) {
        this.pieceLength = pieceLength;
    }

    public List<String> getPieces() {
        return pieces;
    }

    public void setPieces(List<String> pieces) {
        this.pieces = pieces;
    }
}
