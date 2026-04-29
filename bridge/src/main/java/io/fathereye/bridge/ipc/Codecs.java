package io.fathereye.bridge.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;

import java.io.IOException;

/**
 * Single ObjectMapper instances per codec, configured for forward-compat
 * (unknown JSON fields ignored) and field-based POJO discovery (so mapcore
 * POJOs with public final fields serialize without annotations).
 */
public final class Codecs {

    public static final ObjectMapper JSON = new ObjectMapper();
    public static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

    static {
        configure(JSON);
        configure(CBOR);
    }

    private static void configure(ObjectMapper m) {
        m.findAndRegisterModules();
        m.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        m.setVisibility(m.getSerializationConfig().getDefaultVisibilityChecker()
                .withFieldVisibility(com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
                .withGetterVisibility(com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
                .withIsGetterVisibility(com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
                .withSetterVisibility(com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
                .withCreatorVisibility(com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY));
    }

    private Codecs() {}

    public static byte[] encodeJson(Object o) throws IOException {
        return JSON.writeValueAsBytes(o);
    }

    public static <T> T decodeJson(byte[] bytes, Class<T> type) throws IOException {
        return JSON.readValue(bytes, type);
    }

    public static byte[] encodeCbor(Object o) throws IOException {
        return CBOR.writeValueAsBytes(o);
    }

    public static <T> T decodeCbor(byte[] bytes, Class<T> type) throws IOException {
        return CBOR.readValue(bytes, type);
    }
}
