import com.dampcake.bencode.Type;
import com.google.gson.Gson;
import com.dampcake.bencode.Bencode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class TorrentParser {
    private static final Gson gson = new Gson();
    private static final Bencode bencode = new Bencode();

    public Map<String, Object> parseTorrentFile(String filePath) throws Exception {
        String content = Files.readString(Path.of(filePath));
        Map<String, Object> dict = (Map<String, Object>) decodeBencode(content);
        return dict;
    }

    /**
     * Return the decoded Bencoded String
     *
     * @param bencodedString
     * @return Object
     */
    static Object decodeBencode(String bencodedString) {
        EncodingType encodingType = determineEncodingType(bencodedString);
        switch (encodingType) {
            case STRING -> {
                return bencode.decode(bencodedString.getBytes(), Type.STRING);
            }
            case NUMBER -> {
                return bencode.decode(bencodedString.getBytes(), Type.NUMBER);
            }
            case LIST -> {
                return bencode.decode(bencodedString.getBytes(), Type.LIST);
            }
            case DICTIONARY -> {
                return bencode.decode(bencodedString.getBytes(), Type.DICTIONARY);
            }
            case INVALID -> {
                throw new RuntimeException("Invalid encoding!");
            }
        }
        return null;
    }

    /**
     * Determine the encoding type for a Bencoded String
     * @param bencodedString
     * @return EncodingType
     */
    static EncodingType determineEncodingType(String bencodedString) {
        // if the first character is a digit, it is an encoded string
        if (Character.isDigit(bencodedString.charAt(0))) {
            return EncodingType.STRING;
        }
        // if it starts with an 'i' and ends with an 'e', it is an encoded integer
        else if (bencodedString.startsWith("i") && bencodedString.endsWith("e")) {
            return EncodingType.NUMBER;
        }
        // if it starts with an 'l' and ends with an 'e', it is an encoded list
        else if (bencodedString.startsWith("l") && bencodedString.endsWith("e")) {
            return EncodingType.LIST;
        }
        // if it starts with a 'd' and ends with an 'e', it is a dictionary
        else if (bencodedString.startsWith("d") && bencodedString.endsWith("e")) {
            return EncodingType.DICTIONARY;
        }
        else {
            return EncodingType.INVALID;
        }
    }
}
