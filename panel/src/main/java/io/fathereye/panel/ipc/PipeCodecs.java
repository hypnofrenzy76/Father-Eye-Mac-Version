package io.fathereye.panel.ipc;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;

import java.io.IOException;

public final class PipeCodecs {

    public static final ObjectMapper JSON = configure(new ObjectMapper());
    public static final ObjectMapper CBOR = configure(new ObjectMapper(new CBORFactory()));

    private PipeCodecs() {}

    private static ObjectMapper configure(ObjectMapper m) {
        m.findAndRegisterModules();
        m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        m.setVisibility(m.getSerializationConfig().getDefaultVisibilityChecker()
                .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withCreatorVisibility(JsonAutoDetect.Visibility.ANY));
        return m;
    }

    public static byte[] encodeJson(Object o) throws IOException { return JSON.writeValueAsBytes(o); }
    public static <T> T decodeJson(byte[] b, Class<T> type) throws IOException { return JSON.readValue(b, type); }
}
