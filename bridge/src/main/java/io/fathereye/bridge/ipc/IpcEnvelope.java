package io.fathereye.bridge.ipc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Wire envelope for every JSON-codec frame. CBOR-codec frames are bulk
 * payloads keyed by the previous JSON-encoded {@code Request}'s id.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class IpcEnvelope {

    public int v;            // protocol version
    public long id;          // monotonic per-direction
    public String kind;      // Hello | Welcome | Subscribe | ... | Error
    public String topic;     // for Subscribe/Snapshot/Delta/Event/Unsubscribe
    public JsonNode payload; // kind-specific

    public IpcEnvelope() {}

    public IpcEnvelope(int v, long id, String kind, String topic, JsonNode payload) {
        this.v = v;
        this.id = id;
        this.kind = kind;
        this.topic = topic;
        this.payload = payload;
    }

    // ---- common payload DTOs (top-level so Jackson finds them) -----------

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
        public ModInfo(String id, String version) { this.id = id; this.version = version; }
    }

    public static final class ErrorPayload {
        public String code;
        public String message;
        public Long relatedId;
        public ErrorPayload() {}
        public ErrorPayload(String code, String message) { this.code = code; this.message = message; }
    }
}
