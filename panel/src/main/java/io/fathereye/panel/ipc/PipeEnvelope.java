package io.fathereye.panel.ipc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class PipeEnvelope {

    public int v;
    public long id;
    public String kind;
    public String topic;
    public JsonNode payload;

    public PipeEnvelope() {}

    public PipeEnvelope(int v, long id, String kind, String topic, JsonNode payload) {
        this.v = v; this.id = id; this.kind = kind; this.topic = topic; this.payload = payload;
    }

    public static final class HelloPayload {
        public int protocolVersion;
        public String panelVersion;
        public String[] capabilities;
        public HelloPayload() {}
    }

    public static final class WelcomePayload {
        public int protocolVersion;
        public String bridgeVersion;
        public String mcVersion;
        public String forgeVersion;
        public String instanceUuid;
        public String[] capabilities;
        public String[] dimensions;
        public ModInfo[] mods;
        public long serverHeapMaxBytes;
        public WelcomePayload() {}
    }

    public static final class ModInfo {
        public String id;
        public String version;
        public ModInfo() {}
    }

    public static final class ErrorPayload {
        public String code;
        public String message;
        public Long relatedId;
        public ErrorPayload() {}
    }
}
