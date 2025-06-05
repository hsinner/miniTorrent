package com.minitorrent;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class TorrentMsg {

    private MsgType type;
    private int length = -1; // length of block
    private int index = -1; // where the block is
    private int begin = -1; // offset within block
    private byte[] chunk = null; // data
    private byte[] field = null;

    // possible msg types
    public enum MsgType {
        KEEP_ALIVE,
        CHOKE,
        UNCHOKE,
        INTERESTED,
        UNINTERESTED,
        HAVE,
        BITFIELD,
        REQUEST,
        PIECE,
        CANCEL

    }

    // all possible msg forms
    // only msg type
    public TorrentMsg(MsgType type) {
        this.type = type;
    }

    // block transfer msg -- type = request or cancel
    public TorrentMsg(MsgType type, int index, int begin, int length) {
        this.type = type;
        this.index = index;
        this.begin = begin;
        this.length = length;
    }

    // for have msg
    public TorrentMsg(MsgType type, int index) {
        this.type = type;
        this.index = index;
    }

    // for transfering part of file msg type = piece
    public TorrentMsg(MsgType type, int index, int begin, byte[] chunk) {
        this.type = type;
        this.index = index;
        this.begin = begin;
        this.chunk = chunk;
    }

    // for bitfieled msg. where field is a bitfield of peices peer has
    public TorrentMsg(MsgType type, byte[] field) {
        this.type = type;
        this.field = field;
    }

    // getters
    public MsgType getType() {
        return type;
    }

    public int getIndex() {
        return index;
    }

    public int getBegin() {
        return begin;
    }

    public int getLength() {
        return length;
    }

    public byte[] getChunk() {
        return chunk;
    }

    public byte[] getField() {
        return field;
    }

    // taking a message and turning it into bytes so it can be sent
    public byte[] turnIntoBytes() {
        ByteBuffer msg = null;

        // check the type. type to byte specifications found at
        // https://wiki.theory.org/BitTorrentSpecification#choke:_.3Clen.3D0001.3E.3Cid.3D0.3E
        if (type == MsgType.KEEP_ALIVE) {
            // sent periodically to make sure connection doesn't close prematurally
            // length of 0000 and no msg id is exchanged
            msg = ByteBuffer.allocate(4);
            msg.putInt(0);

        } else if (type == MsgType.CHOKE) {
            // prevents data transfer
            // len=0001 id=0
            msg = ByteBuffer.allocate(5);
            msg.putInt(1);
            msg.put((byte) 0);

        } else if (type == MsgType.UNCHOKE) {
            // allows data transfer
            // len=0001 id=1
            msg = ByteBuffer.allocate(5);
            msg.putInt(1);
            msg.put((byte) 1);

        } else if (type == MsgType.INTERESTED) {
            // is requesting data
            // len=0001 id=2
            msg = ByteBuffer.allocate(5);
            msg.putInt(1);
            msg.put((byte) 2);

        } else if (type == MsgType.UNINTERESTED) {
            // is not requesting data.
            // len=0001 id=3
            msg = ByteBuffer.allocate(5);
            msg.putInt(1);
            msg.put((byte) 3);

        } else if (type == MsgType.HAVE) {
            // informs peers of the presence of a specific piece
            // len=0005 id=4 <piece index> index=4 bytes
            msg = ByteBuffer.allocate(9);
            msg.putInt(5);
            msg.put((byte) 4);
            msg.putInt(this.index);

        } else if (type == MsgType.BITFIELD) {
            // inital list of pieces held by a peer
            // len=0001+X id=5 <bitfield>, X is the len of the bitfield
            msg = ByteBuffer.allocate(5 + field.length);
            msg.putInt(1 + field.length);
            msg.put((byte) 5);
            msg.put(field, 0, field.length);

        } else if (type == MsgType.REQUEST) {
            // requests a specific block from peer
            // len=0013 id=6 <index><begin><length>, each 4 bytes
            msg = ByteBuffer.allocate(17);
            msg.putInt(13);
            msg.put((byte) 6);
            msg.putInt(index);
            msg.putInt(begin);
            msg.putInt(length);

        } else if (type == MsgType.PIECE) {
            // contains the requested piece of data
            // len=0009+X id=7 <index><begin><block>, X is len of chunk,
            // index, begin, block = 4 bytes
            msg = ByteBuffer.allocate(13 + chunk.length);
            msg.putInt(9 + chunk.length);
            msg.put((byte) 7);
            msg.putInt(index);
            msg.putInt(begin);
            msg.put(chunk, 0, chunk.length);

        } else if (type == MsgType.CANCEL) {
            // cancels a previously requested piece
            // len=0013 id=8 <index><begin><length>
            msg = ByteBuffer.allocate(17);
            msg.putInt(13);
            msg.put((byte) 8);
            msg.putInt(index);
            msg.putInt(begin);
            msg.putInt(length);

        } else {
            System.out.println("Message type isn't   known");
            throw new RuntimeException("Message type problem in TorrentMsg. Type was " + type);
        }

        // return byte message
        byte[] arr = msg.array();
        System.out.println("msg array: " + Arrays.toString(arr));
        return arr;
    }

    // takes byte msg and turns it into a readable msg
    public static TorrentMsg turnIntoMsg(byte[] msg) {
        ByteBuffer buf = ByteBuffer.wrap(msg);
        int len = buf.getInt();

        // if len = 0 it's a keep alive msg
        if (len == 0) {
            return new TorrentMsg(MsgType.KEEP_ALIVE);
        }

        // Read message ID as a byte instead of string
        byte id = buf.get();

        // check which id it is -- status msgs only
        // choke, unchoke, interested, uninterested
        if (len == 1) {
            switch (id) {
                case 0: // choke
                    return new TorrentMsg(MsgType.CHOKE);
                case 1: // unchoke
                    return new TorrentMsg(MsgType.UNCHOKE);
                case 2: // interested
                    return new TorrentMsg(MsgType.INTERESTED);
                case 3: // uninterested
                    return new TorrentMsg(MsgType.UNINTERESTED);
                default:
                    throw new RuntimeException("id of msg, len 1, was not knwon");
            }

        }

        // for msgs with info -- have, bitfield, request, cancel, piece
        // for request and cancel
        int index;
        int begin;
        int length;
        System.out.println("this is the id: " + id);
        switch (id) {
            case 4: // have -- need to extract piece index
                int peiceInt = buf.getInt();
                System.out.println("I received HAVE");
                return new TorrentMsg(MsgType.HAVE, peiceInt);

            case 5: // bitfield -- need to extract bitfield len
                // but also need to extract the actual bitfield to know the pieces a peer has
                byte[] pieces = new byte[len - 1];
                buf.get(pieces);
                System.out.print("I received BITFIELD");
                // System.put.println()
                return new TorrentMsg(MsgType.BITFIELD, pieces);

            case 6: // request -- index, begin, length
                index = buf.getInt();
                begin = buf.getInt();
                length = buf.getInt();
                return new TorrentMsg(MsgType.REQUEST, index, begin, length);

            case 7: // piece -- index, begin, data
                index = buf.getInt();
                begin = buf.getInt();
                byte[] data = new byte[len - 9];
                buf.get(data, 0, len - 9);
                return new TorrentMsg(MsgType.PIECE, index, begin, data);

            case 8: // cancel -- index, begin, length
                index = buf.getInt();
                begin = buf.getInt();
                length = buf.getInt();
                return new TorrentMsg(MsgType.CANCEL, index, begin, length);

            default:
                throw new RuntimeException("id of msg was not known");
        }

    }

}
