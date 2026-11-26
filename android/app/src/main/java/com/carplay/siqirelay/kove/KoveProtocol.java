package com.carplay.siqirelay.kove;

import java.nio.charset.StandardCharsets;

public class KoveProtocol {

    public static byte[] wrapFrame(byte[] payload) {
        byte[] frame = new byte[payload.length + 7];
        frame[0] = (byte) 0xEE;
        frame[1] = (byte) 0xFD;
        frame[2] = (byte) ((payload.length >> 24) & 0xFF);
        frame[3] = (byte) ((payload.length >> 16) & 0xFF);
        frame[4] = (byte) ((payload.length >> 8) & 0xFF);
        frame[5] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, frame, 6, payload.length);
        frame[frame.length - 1] = (byte) 0xFF;
        return frame;
    }

    public static byte[] makeDeviceTypePacket() {
        byte[] bArr = new byte[10];
        bArr[0] = 1;
        bArr[1] = 23;
        writeInt(4, bArr, 2);
        writeInt(2, bArr, 6); // 2 = Android
        return bArr;
    }

    public static byte[] makeLinkInfoPacket(String nickname) {
        byte[] header = new byte[]{0x01, 0x0C, 0x00, 0x00, 0x01, 0x00};
        byte[] padded = new byte[256];
        byte[] nameBytes = (nickname != null ? nickname : "CarPlay-Bridge").getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, padded, 0, Math.min(nameBytes.length, 256));
        byte[] out = new byte[header.length + padded.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(padded, 0, out, header.length, padded.length);
        return out;
    }

    public static byte[] makeFirmwareRequestPacket() {
        return new byte[]{0x01, 0x01, 0x00, 0x00, 0x00, 0x00};
    }

    public static byte[] makeKeepalivePacket() {
        return new byte[]{0x02, 0x01, 0x00, 0x00, 0x00, 0x00};
    }

    public static byte[] makeNaviStartPacket() {
        String naviStart = "{\"msg_id\":27,\"func\":\"NAVI\",\"CMD\":\"inside_naviinfo\"," +
                "\"start\":\"30.0,120.0\",\"waynum\":0,\"end\":\"30.1,120.1\"," +
                "\"routeId\":1,\"strategy\":0,\"dst\":\"Apple CarPlay\"," +
                "\"naviType\":1,\"carType\":1,\"isRestricted\":0,\"carNumber\":\"\",\"carCC\":450,\"themeMode\":0}";
        return wrapFrame(naviStart.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] makeNaviInfoPacket(int turnIcon, String nextRoad, int turnDist, int totalDist) {
        byte[] packet = new byte[274];
        packet[0] = 3;
        packet[1] = 1;
        writeInt(268, packet, 2);
        writeInt(turnIcon, packet, 6);
        writeInt(turnDist, packet, 10);
        writeInt(totalDist, packet, 14);
        writeInt(1800, packet, 18);

        byte[] roadBytes = (nextRoad != null ? nextRoad : "Apple CarPlay").getBytes(StandardCharsets.UTF_8);
        System.arraycopy(roadBytes, 0, packet, 22, Math.min(roadBytes.length, 252));
        return packet;
    }

    public static byte[] makeVideoFormatHeader(int width, int height) {
        byte[] sizeHeader = new byte[69];
        byte[] tag = "android".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(tag, 0, sizeHeader, 1, tag.length);
        sizeHeader[65] = (byte) (width >> 8);
        sizeHeader[66] = (byte) (width & 0xFF);
        sizeHeader[67] = (byte) (height >> 8);
        sizeHeader[68] = (byte) (height & 0xFF);
        return sizeHeader;
    }

    private static void writeInt(int val, byte[] buf, int offset) {
        buf[offset] = (byte) ((val >> 24) & 0xFF);
        buf[offset + 1] = (byte) ((val >> 16) & 0xFF);
        buf[offset + 2] = (byte) ((val >> 8) & 0xFF);
        buf[offset + 3] = (byte) (val & 0xFF);
    }
}
