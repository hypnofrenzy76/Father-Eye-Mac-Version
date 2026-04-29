package io.fathereye.bridge.ipc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Length-prefixed framing for the named-pipe IPC.
 * <pre>
 *   [u32 BE length][u8 codec][payload of `length` bytes]
 * </pre>
 * Length excludes itself and the codec byte.
 */
public final class Frame {

    public static final byte CODEC_JSON = 0x00;
    public static final byte CODEC_CBOR = 0x01;

    public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024; // 16 MiB

    public final byte codec;
    public final byte[] payload;

    public Frame(byte codec, byte[] payload) {
        this.codec = codec;
        this.payload = payload;
    }

    public byte[] encode() {
        if (payload.length > MAX_FRAME_BYTES) {
            throw new IllegalStateException("Frame too large: " + payload.length);
        }
        ByteBuffer buf = ByteBuffer.allocate(4 + 1 + payload.length).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(payload.length);
        buf.put(codec);
        buf.put(payload);
        return buf.array();
    }

    /**
     * Decode the 5-byte header into the payload length. Caller must then
     * read exactly that many bytes for the payload.
     */
    public static int decodeHeaderLength(byte[] header5) throws IOException {
        if (header5 == null || header5.length != 5) {
            throw new IOException("frame header must be 5 bytes, got " + (header5 == null ? -1 : header5.length));
        }
        int len = ByteBuffer.wrap(header5, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        if (len < 0 || len > MAX_FRAME_BYTES) {
            throw new IOException("frame length out of range: " + len);
        }
        return len;
    }

    public static byte decodeHeaderCodec(byte[] header5) {
        return header5[4];
    }
}
