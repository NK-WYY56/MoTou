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
}
