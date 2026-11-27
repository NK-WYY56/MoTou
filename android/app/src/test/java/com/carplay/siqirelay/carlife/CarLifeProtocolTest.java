package com.carplay.siqirelay.carlife;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

public class CarLifeProtocolTest {

    @Test
    public void roundTripControl8ByteAndVideo12ByteMessages() throws Exception {
        byte[] proto = new CarLifeProtocol.ProtoWriter()
                .writeInt(1, 3)
                .writeInt(2, 0)
                .toByteArray();

        byte[] cmdWire = CarLifeProtocol.encodeMessage(
                CarLifeProtocol.CH_CMD, CarLifeProtocol.MSG_CMD_HU_PROTOCOL_VERSION, proto);
        assertEquals(8 + proto.length, cmdWire.length);

        CarLifeProtocol.Message decodedCmd = CarLifeProtocol.readMessage(
                new ByteArrayInputStream(cmdWire), CarLifeProtocol.CH_CMD);
        assertNotNull(decodedCmd);
        assertEquals(CarLifeProtocol.MSG_CMD_HU_PROTOCOL_VERSION, decodedCmd.id);

        CarLifeProtocol.ProtoReader cmdReader = CarLifeProtocol.ProtoReader.parse(decodedCmd.payload);
        assertEquals(3, cmdReader.getInt(1, -1));
        assertEquals(0, cmdReader.getInt(2, -1));

        byte[] fakeNal = new byte[]{0, 0, 0, 1, 0x65, 0x11, 0x22, 0x33};
        byte[] vidWire = CarLifeProtocol.encodeMessage(
                CarLifeProtocol.CH_VIDEO, CarLifeProtocol.MSG_VIDEO_DATA, fakeNal);
        assertEquals(12 + fakeNal.length, vidWire.length);

        CarLifeProtocol.Message decodedVid = CarLifeProtocol.readMessage(
                new ByteArrayInputStream(vidWire), CarLifeProtocol.CH_VIDEO);
        assertNotNull(decodedVid);
        assertEquals(CarLifeProtocol.MSG_VIDEO_DATA, decodedVid.id);
        assertArrayEquals(fakeNal, decodedVid.payload);
    }

    @Test
    public void nestedFeatureConfigProtoRoundTrip() throws Exception {
        byte[] item1 = new CarLifeProtocol.ProtoWriter()
                .writeString(1, "CONTENT_ENCRYPTION")
                .writeInt(2, 0)
                .toByteArray();
        byte[] item2 = new CarLifeProtocol.ProtoWriter()
                .writeString(1, "CONNECT_TYPE")
                .writeInt(2, 5)
                .toByteArray();
        byte[] payload = new CarLifeProtocol.ProtoWriter()
                .writeInt(1, 2)
                .writeBytes(2, item1)
                .writeBytes(2, item2)
                .toByteArray();

        CarLifeProtocol.ProtoReader reader = CarLifeProtocol.ProtoReader.parse(payload);
        assertEquals(2, reader.getInt(1, 0));
        List<CarLifeProtocol.ProtoReader> nested = reader.getMessages(2);
        assertEquals(2, nested.size());
        assertEquals("CONTENT_ENCRYPTION", nested.get(0).getString(1));
        assertEquals(0, nested.get(0).getInt(2, -1));
        assertEquals("CONNECT_TYPE", nested.get(1).getString(1));
        assertEquals(5, nested.get(1).getInt(2, -1));
    }

    @Test(expected = IOException.class)
    public void rejectsOversizedFrameLengthHeader() throws Exception {
        byte[] badHeader = new byte[]{
                0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                0, 0, 0, 0,
                0, 2, 0, 1
        };
        CarLifeProtocol.readMessage(new ByteArrayInputStream(badHeader), CarLifeProtocol.CH_VIDEO);
    }

    @Test
    public void roundTripTouchAndHardKeyMessages() throws Exception {
        byte[] touchPayload = new CarLifeProtocol.ProtoWriter()
                .writeInt(1, 5)   // ACTION_POINTER_DOWN
                .writeInt(2, 640) // x0
                .writeInt(3, 280) // y0
                .writeInt(4, 800) // x1
                .writeInt(5, 320) // y1
                .toByteArray();
        byte[] touchWire = CarLifeProtocol.encodeMessage(
                CarLifeProtocol.CH_TOUCH, CarLifeProtocol.MSG_TOUCH_ACTION, touchPayload);
        assertEquals(8 + touchPayload.length, touchWire.length);

        CarLifeProtocol.Message decodedTouch = CarLifeProtocol.readMessage(
                new ByteArrayInputStream(touchWire), CarLifeProtocol.CH_TOUCH);
        assertNotNull(decodedTouch);
        assertEquals(CarLifeProtocol.MSG_TOUCH_ACTION, decodedTouch.id);
        CarLifeProtocol.ProtoReader tr = CarLifeProtocol.ProtoReader.parse(decodedTouch.payload);
        assertEquals(5, tr.getInt(1, -1));
        assertEquals(640, tr.getInt(2, -1));
        assertEquals(280, tr.getInt(3, -1));
        assertEquals(800, tr.getInt(4, -1));
        assertEquals(320, tr.getInt(5, -1));

        for (int code : new int[]{3, 4, 231}) {
            byte[] keyPayload = new CarLifeProtocol.ProtoWriter().writeInt(1, code).toByteArray();
            byte[] keyWire = CarLifeProtocol.encodeMessage(
                    CarLifeProtocol.CH_TOUCH, CarLifeProtocol.MSG_TOUCH_CAR_HARD_KEY_CODE, keyPayload);
            CarLifeProtocol.Message decodedKey = CarLifeProtocol.readMessage(
                    new ByteArrayInputStream(keyWire), CarLifeProtocol.CH_TOUCH);
            assertNotNull(decodedKey);
            assertEquals(CarLifeProtocol.MSG_TOUCH_CAR_HARD_KEY_CODE, decodedKey.id);
            assertEquals(code, CarLifeProtocol.ProtoReader.parse(decodedKey.payload).getInt(1, -1));
        }
    }
}
