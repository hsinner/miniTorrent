# csse432BitTorrent
A mini-version of the BitTorrent

good doc -- https://www.bittorrent.org/beps/bep_0003.html

-- torrent file -- for each file 
-- tracker 
    -- file splitter 
-- server for client
-- client for client 
    When a peer finishes downloading a piece and checks that the hash matches, it announces that it has that piece to all of its peers


Read file data: Read the content of the file to be shared.
Divide into pieces: Split the file into fixed-size pieces (e.g., 256KB, 512KB, 1MB).
Calculate piece hashes: Generate SHA-1 hashes for each piece.
Create metadata dictionary: Construct a dictionary (map) containing the metadata. This dictionary should include the tracker URL, file information, and the list of piece hashes.
Bencode the metadata: Encode the metadata dictionary using the Bencoding format. Bencoding is a serialization format used in torrent files.
Write to file: Save the bencoded data to a .torrent file.

pstrlen (1 byte): Length of the protocol string
protocol (19 bytes): The actual “BitTorrent protocol” string
reserved (8 bytes): Reserved for future extensions
info_hash (20 bytes): A SHA1 hash of the info dictionary in the torrent file
peer_id (20 bytes): A unique ID for the client