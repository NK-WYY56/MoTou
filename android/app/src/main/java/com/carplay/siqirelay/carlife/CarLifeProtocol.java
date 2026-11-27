package com.carplay.siqirelay.carlife;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Baidu CarLife wire framing (8-byte ch 1/6, 12-byte ch 2/3/4/5/7), message IDs,
 * and zero-dependency Protobuf subset encoder/decoder matching OpenXPlay/CarLife specs.
 */
public final class CarLifeProtocol {
    public static final int[] PORTS = {7240, 8240, 9240, 9241, 9242, 9340, 9440};
    public static final int CH_CMD = 1;
    public static final int CH_VIDEO = 2;
    public static final int CH_MEDIA = 3;
    public static final int CH_TTS = 4;
    public static final int CH_VR = 5;
    public static final int CH_TOUCH = 6;
    public static final int CH_DATA = 7;

    public static final int UDP_BEACON_PORT = 7999;
    public static final int UDP_QUERY_PORT = 8999;

    // Control messages (Channel 1)
    public static final int MSG_CMD_HU_PROTOCOL_VERSION = 98305;          // 0x00018001 HU -> MD
    public static final int MSG_CMD_MD_PROTOCOL_VERSION_MATCH_STATUS = 65538; // 0x00010002 MD -> HU
    public static final int MSG_CMD_HU_INFO = 98307;                      // 0x00018003 HU -> MD
    public static final int MSG_CMD_MD_INFO = 65540;                      // 0x00010004 MD -> HU
    public static final int MSG_CMD_VIDEO_ENCODER_INIT = 98311;           // 0x00018007 HU -> MD
    public static final int MSG_CMD_VIDEO_ENCODER_INIT_DONE = 65544;      // 0x00010008 MD -> HU
    public static final int MSG_CMD_VIDEO_ENCODER_START = 98313;          // 0x00018009 HU -> MD
    public static final int MSG_CMD_VIDEO_ENCODER_PAUSE = 98314;          // 0x0001800A HU -> MD
    public static final int MSG_CMD_VIDEO_ENCODER_RESET = 98315;          // 0x0001800B HU -> MD

    public static final int MSG_CMD_HU_AUTHEN_REQUEST = 98376;            // 0x00018048 HU -> MD
    public static final int MSG_CMD_MD_AUTHEN_RESPONSE = 65609;           // 0x00010049 MD -> HU
    public static final int MSG_CMD_HU_AUTHEN_RESULT = 98378;             // 0x0001804A HU -> MD
    public static final int MSG_CMD_MD_AUTHEN_RESULT = 65611;             // 0x0001004B MD -> HU
    public static final int MSG_CMD_AUTH_FINISH = 98380;                  // 0x0001804C HU -> MD
    public static final int MSG_CMD_MD_FEATURE_CONFIG_RESPONSE = 65617;   // 0x00010051 MD -> HU
    public static final int MSG_CMD_HU_FEATURE_CONFIG_REQUEST = 98386;    // 0x00018052 HU -> MD
    public static final int MSG_CMD_HU_CONNECT_EXCEPTION = 98389;         // 0x00018055 HU -> MD

    // Video messages (Channel 2)
    public static final int MSG_VIDEO_DATA = 131073;                      // 0x00020001 MD -> HU
    public static final int MSG_VIDEO_HEARTBEAT = 131074;                 // 0x00020002 Bidirectional

    // Touch / Hard-key messages (Channel 6)
    public static final int MSG_TOUCH_ACTION = 425985;                    // 0x00068001 HU -> MD
    public static final int MSG_TOUCH_CAR_HARD_KEY_CODE = 425992;         // 0x00068008 HU -> MD

    public static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;

    private CarLifeProtocol() {}

    public static int headerSize(int channel) {
        return (channel == CH_CMD || channel == CH_TOUCH) ? 8 : 12;
    }

    public static final class Message {
        public final int channel;
        public final int id;
        public final byte[] payload;

        public Message(int channel, int id, byte[] payload) {
            this.channel = channel;
            this.id = id;
            this.payload = payload != null ? payload : new byte[0];
        }
    }

    public static byte[] encodeMessage(int channel, int id, byte[] payload) throws IOException {
        byte[] body = payload != null ? payload : new byte[0];
        int header = headerSize(channel);
        if (header == 8) {
            if (body.length > 65535) {
                throw new IOException("CarLife 8B payload too large: " + body.length);
            }
            byte[] packet = new byte[8 + body.length];
            packet[0] = (byte) ((body.length >> 8) & 0xFF);
            packet[1] = (byte) (body.length & 0xFF);
            packet[2] = 0;
            packet[3] = 0;
            packet[4] = (byte) ((id >> 24) & 0xFF);
            packet[5] = (byte) ((id >> 16) & 0xFF);
            packet[6] = (byte) ((id >> 8) & 0xFF);
            packet[7] = (byte) (id & 0xFF);
            if (body.length > 0) {
                System.arraycopy(body, 0, packet, 8, body.length);
            }
            return packet;
        } else {
            if (body.length > MAX_PAYLOAD_BYTES) {
                throw new IOException("CarLife 12B payload too large: " + body.length);
            }
            byte[] packet = new byte[12 + body.length];
            packet[0] = (byte) ((body.length >> 24) & 0xFF);
            packet[1] = (byte) ((body.length >> 16) & 0xFF);
            packet[2] = (byte) ((body.length >> 8) & 0xFF);
            packet[3] = (byte) (body.length & 0xFF);
            // bytes 4..7 reserved = 0
            packet[8] = (byte) ((id >> 24) & 0xFF);
            packet[9] = (byte) ((id >> 16) & 0xFF);
            packet[10] = (byte) ((id >> 8) & 0xFF);
            packet[11] = (byte) (id & 0xFF);
            if (body.length > 0) {
                System.arraycopy(body, 0, packet, 12, body.length);
            }
            return packet;
        }
    }

    public static void writeMessage(OutputStream out, int channel, int id, byte[] payload) throws IOException {
        out.write(encodeMessage(channel, id, payload));
        out.flush();
    }

    public static Message readMessage(InputStream in, int channel) throws IOException {
        DataInputStream din = (in instanceof DataInputStream) ? (DataInputStream) in : new DataInputStream(in);
        int hdrSize = headerSize(channel);
        byte[] hdr = new byte[hdrSize];
        try {
            din.readFully(hdr);
        } catch (EOFException eof) {
            return null;
        }

        int len;
        int id;
        if (hdrSize == 8) {
            len = ((hdr[0] & 0xFF) << 8) | (hdr[1] & 0xFF);
            id = ((hdr[4] & 0xFF) << 24) | ((hdr[5] & 0xFF) << 16) | ((hdr[6] & 0xFF) << 8) | (hdr[7] & 0xFF);
        } else {
            len = ((hdr[0] & 0xFF) << 24) | ((hdr[1] & 0xFF) << 16) | ((hdr[2] & 0xFF) << 8) | (hdr[3] & 0xFF);
            id = ((hdr[8] & 0xFF) << 24) | ((hdr[9] & 0xFF) << 16) | ((hdr[10] & 0xFF) << 8) | (hdr[11] & 0xFF);
        }

        if (len < 0 || len > MAX_PAYLOAD_BYTES) {
            throw new IOException("Invalid CarLife payload length: " + len + " on ch=" + channel);
        }
        byte[] payload = new byte[len];
        if (len > 0) {
            din.readFully(payload);
        }
        return new Message(channel, id, payload);
    }

    // ---- Protobuf subset encoder & decoder ----

    public static final class ProtoWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private void writeVarint(long value) {
            long v = value;
            while ((v & ~0x7FL) != 0) {
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            out.write((int) (v & 0x7F));
        }

        public ProtoWriter writeInt(int field, long value) {
            writeVarint(((long) field) << 3);
            writeVarint(value);
            return this;
        }

        public ProtoWriter writeString(int field, String value) {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            return writeBytes(field, bytes);
        }

        public ProtoWriter writeBytes(int field, byte[] value) {
            byte[] b = value != null ? value : new byte[0];
            writeVarint((((long) field) << 3) | 2L);
            writeVarint(b.length);
            out.write(b, 0, b.length);
            return this;
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    public static final class ProtoReader {
        private final Map<Integer, Long> integers = new HashMap<>();
        private final Map<Integer, List<byte[]>> blobs = new HashMap<>();

        private ProtoReader() {}

        public static ProtoReader parse(byte[] data) throws IOException {
            ProtoReader reader = new ProtoReader();
            if (data == null || data.length == 0) return reader;
            Cursor cursor = new Cursor(data);
            while (cursor.pos < data.length) {
                readField(cursor, reader, 0, true);
            }
            return reader;
        }

        private static final class Cursor {
            final byte[] data;
            int pos = 0;

            Cursor(byte[] data) {
                this.data = data;
            }

            long readVarint() throws IOException {
                long result = 0;
                int shift = 0;
                for (int i = 0; i < 10; i++) {
                    if (pos >= data.length) throw new IOException("Truncated protobuf varint");
                    int b = data[pos++] & 0xFF;
                    if (i == 9 && b > 1) throw new IOException("Overflow protobuf varint");
                    result |= ((long) (b & 0x7F)) << shift;
                    if ((b & 0x80) == 0) return result;
                    shift += 7;
                }
                throw new IOException("Malformed protobuf varint");
            }

            void skip(int count) throws IOException {
                if (count < 0 || count > data.length - pos) throw new IOException("Invalid protobuf skip");
                pos += count;
            }
        }

        private static void readField(Cursor c, ProtoReader dst, int depth, boolean collect) throws IOException {
            if (depth >= 32) throw new IOException("Protobuf recursion depth exceeded");
            long tag = c.readVarint();
            int field = (int) (tag >>> 3);
            int wireType = (int) (tag & 7);
            if (field <= 0) throw new IOException("Invalid protobuf field number");

            switch (wireType) {
                case 0: {
                    long val = c.readVarint();
                    if (collect) dst.integers.put(field, val);
                    break;
                }
                case 1:
                    c.skip(8);
                    break;
                case 2: {
                    long lenLong = c.readVarint();
                    if (lenLong < 0 || lenLong > c.data.length - c.pos) {
                        throw new IOException("Invalid protobuf length-delimited field");
                    }
                    int len = (int) lenLong;
                    byte[] copy = new byte[len];
                    System.arraycopy(c.data, c.pos, copy, 0, len);
                    c.pos += len;
                    if (collect) {
                        List<byte[]> list = dst.blobs.get(field);
                        if (list == null) {
                            list = new ArrayList<>();
                            dst.blobs.put(field, list);
                        }
                        list.add(copy);
                    }
                    break;
                }
                case 3:
                    while (true) {
                        long innerTag = c.readVarint();
                        int innerWire = (int) (innerTag & 7);
                        int innerField = (int) (innerTag >>> 3);
                        if (innerWire == 4) {
                            if (innerField != field) throw new IOException("Mismatched group end");
                            break;
                        }
                        c.pos -= varintSize(innerTag);
                        readField(c, dst, depth + 1, false);
                    }
                    break;
                case 5:
                    c.skip(4);
                    break;
                default:
                    throw new IOException("Unsupported protobuf wire type: " + wireType);
            }
        }

        private static int varintSize(long v) {
            int size = 1;
            while ((v & ~0x7FL) != 0) {
                size++;
                v >>>= 7;
            }
            return size;
        }

        public Long getLong(int field) {
            return integers.get(field);
        }

        public int getInt(int field, int fallback) {
            Long v = integers.get(field);
            return v != null ? v.intValue() : fallback;
        }

        public String getString(int field) {
            List<byte[]> list = blobs.get(field);
            if (list == null || list.isEmpty()) return null;
            return new String(list.get(list.size() - 1), StandardCharsets.UTF_8);
        }

        public List<ProtoReader> getMessages(int field) {
            List<byte[]> list = blobs.get(field);
            if (list == null || list.isEmpty()) return Collections.emptyList();
            List<ProtoReader> out = new ArrayList<>(list.size());
            for (byte[] item : list) {
                try {
                    out.add(parse(item));
                } catch (IOException ignored) {}
            }
            return out;
        }
    }
}
