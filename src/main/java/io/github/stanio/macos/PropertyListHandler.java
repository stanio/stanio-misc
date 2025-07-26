package io.github.stanio.macos;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * {@code <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
 *                        "https://www.apple.com/DTDs/PropertyList-1.0.dtd">}
 */
abstract class PropertyListHandler extends ContentModelHandler {

    static final Set<String> CM_ROOT = setOf("plist");
    static final Set<String> CM_KEY = setOf("key");
    static final Set<String> CM_VALUE = setOf("array", "data", "date",
            "dict", "real", "integer", "string", "true", "false");

    static final Set<String> TEXT_ELEMS = setOf("key", "data", "date",
            "real", "integer", "string", "true", "false");
    static final Set<String> OBJ_ELEMS = setOf("array", "dict");

    static final List<String> DICT_CONTEXT = listOf("dict", "*");

    private static final ThreadLocal<Base64.Decoder>
            localDecoder = ThreadLocal.withInitial(Base64::getMimeDecoder);

    protected final List<Object> propertyName = new ArrayList<>();

    @Override
    public void startDocument() throws SAXException {
        super.startDocument();
        firstChild(CM_ROOT);
    }

    @Override
    protected Set<String> elementStart(String name) throws SAXException {
        Set<String> child;
        if (TEXT_ELEMS.contains(name)) {
            resetTextContent();
            child = CM_TEXT;
        } else if ("dict".equals(name)) {
            boolean process = startCollection(propertyName, name);
            propertyName.add("");
            child = process ? CM_KEY : CM_IGNORE;
        } else if ("array".equals(name)) {
            boolean process = startCollection(propertyName, name);
            propertyName.add(0);
            child = process ? CM_VALUE : CM_IGNORE;
        } else if (elementPath.size() == 1) {
            child = CM_VALUE;
        } else {
            throw new IllegalStateException('<' + name + "> not handled");
        }
        return child;
    }

    @Override
    protected Set<String> elementEnd(String name) throws SAXException {
        Set<String> next;
        if ("key".equals(name)) {
            propertySetLast(textContent().trim());
            next = CM_VALUE;
        } else if (OBJ_ELEMS.contains(name)) {
            propertyRemoveLast();
            endCollection(propertyName);
            next = nextKeyOrValue();
        } else if (TEXT_ELEMS.contains(name)) {
            value(propertyName, name, textContent().trim());
            next = nextKeyOrValue();
        } else {
            next = contentModel();
        }
        return next;
    }

    @Override
    protected Set<String> invalidElement(String name) throws SAXException {
        if (matchEnd(elementPath, DICT_CONTEXT)) {
            nextSibling(CM_KEY);
            if (name.equals("key")) {
                return elementStart(name);
            }
        }
        return super.invalidElement(name);
    }


    protected final Object propertyRemoveLast() {
        return propertyName.remove(propertyName.size() - 1);
    }

    protected final void propertySetLast(Object key) {
        propertyName.set(propertyName.size() - 1, key);
    }

    protected final Object propertyLastKey() {
        return propertyName.get(propertyName.size() - 1);
    }

    private Set<String> nextKeyOrValue() {
        if (propertyName.isEmpty())
            return CM_IGNORE;

        Object key = propertyLastKey();
        if (key instanceof Integer) {
            propertySetLast((int) key + 1);
            return CM_VALUE;
        } else { // (key instanceof String)
            propertySetLast("");
            return CM_KEY;
        }
    }

    /**
     * ...
     *
     * @param   name  the qualified property name, or empty if top-level value
     * @param   type  the type of collection (dict | array)
     * @return  whether to parse the content of the collection or not
     * @throws  SAXException  if the given event results in invalid data structure
     */
    protected boolean startCollection(List<Object> name, String type) throws SAXException {
        return false;
    }

    /**
     * ...
     *
     * @param   name  the qualified property name, or empty if top-level value
     * @throws  SAXException  if the given event results in invalid data structure
     */
    protected void endCollection(List<Object> name) throws SAXException {
        // no-op
    }

    /**
     * ...
     *
     * @param   name  qualified property name
     * @param   type  {@code data | date | real | integer | string | true | false }
     * @param   data  the literal (string) value
     * @throws  SAXException  if parsing the literal data according to the given type fails
     */
    protected abstract void value(List<Object> name, String type, CharSequence data)
            throws SAXException;

    protected final Object decodeValue(String type, CharSequence data) throws SAXException {
        switch (type) {
        case "string":
            return data.toString().trim();
        case "integer":
            try {
                return Integer.valueOf(data.toString().trim());
            } catch (NumberFormatException e) {
                throw parseException(e.getMessage(), e);
            }
        case "real":
            try {
                return Double.valueOf(data.toString().trim());
            } catch (NumberFormatException e) {
                throw parseException(e.getMessage(), e);
            }
        case "true":
            return true;
        case "false":
            return false;
        case "date":
            try {
                return ZonedDateTime.parse(data.toString().trim());
            } catch (DateTimeParseException e) {
                throw parseException(e.getMessage(), e);
            }
        case "data":
            return decodeBase64(data);
        default:
            throw new IllegalArgumentException("Unsupported value type: " + type);
        }
    }

    protected final ByteBuffer decodeBase64(CharSequence data) throws SAXException {
        byte[] buf = new byte[(data.length() + 3) / 4 * 3];
        int size = 0;
        try (ByteCharInputStream base64 = new ByteCharInputStream(data);
                InputStream decoded = localDecoder.get().wrap(base64)) {
            int numRead;
            while ((numRead = decoded.read(buf, size, buf.length - size)) != -1) {
                size += numRead;
            }
        } catch (IOException e) {
            throw parseException(e.getMessage(), e);
        }
        return ByteBuffer.wrap(buf, 0, size);
    }

    @Override
    public InputSource resolveEntity(String publicId, String systemId)
            throws IOException, SAXException {
        if ("-//Apple//DTD PLIST 1.0//EN".equalsIgnoreCase(publicId)
                || systemId.matches("(?i)https?://www\\.apple\\.com/DTDs/PropertyList-1\\.0\\.dtd([?#].*)?")) {
            return new InputSource(PropertyListHandler.class.getResource("PropertyList-1.0.dtd").toString());
        }
        return new InputSource(new StringReader(""));
    }

    protected static String fileName(SAXParseException exception) {
        String fileName = exception.getSystemId();
        if (fileName == null) return null;

        try {
            URI uri = new URI(fileName);
            if (uri.getScheme().equals("file")) {
                return Paths.get(uri).getFileName().toString();
            } else if (uri.getPath() != null) {
                return fileName(uri.getPath());
            } else {
                return fileName(uri.getSchemeSpecificPart());
            }
        } catch (URISyntaxException | InvalidPathException e) {
            return fileName;
        }
    }

    private static String fileName(String spec) {
        try {
            return Paths.get(spec).getFileName().toString();
        } catch (InvalidPathException e) {
            return spec;
        }
    }

}


/**
 * Performs direct char -> byte copy.
 */
class ByteCharInputStream extends InputStream {

    private final CharSequence data;
    private final int limit;
    private int pos;

    ByteCharInputStream(CharSequence data) {
        this.data = data;
        this.limit = data.length();
    }

    @Override
    public int read() {
        return (pos < limit) ? data.charAt(pos++) & 0xFF : -1;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (pos < len)
            return -1;

        final int size = Math.min(len, limit - pos);
        int end = off + size;
        CharSequence src = data;
        for (int i = off, p = pos; i < end; i++, p++) {
            b[i] = (byte) src.charAt(p);
        }
        pos += size;
        return size;
    }

}
