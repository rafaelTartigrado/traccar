/*
 * Copyright 2015 Anton Tananaev (anton.tananaev@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import java.nio.ByteOrder;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.traccar.BaseProtocolDecoder;
import org.traccar.model.Event;
import org.traccar.model.Position;

public class BceProtocolDecoder extends BaseProtocolDecoder {

    public BceProtocolDecoder(String protocol) {
        super(protocol);
    }

    private static final int DATA_TYPE = 7;

    private static final int MSG_ASYNC_STACK = 0xA5;
    private static final int MSG_STACK_COFIRM = 0x19;
    private static final int MSG_TIME_TRIGGERED = 0xA0;
    private static final int MSG_OUTPUT_CONTROL = 0x41;
    private static final int MSG_OUTPUT_CONTROL_ACK = 0xC1;

    private static boolean checkBit(int mask, int bit) {
        return (mask & (1 << bit)) != 0;
    }

    @Override
    protected Object decode(
            ChannelHandlerContext ctx, Channel channel, Object msg)
            throws Exception {

        ChannelBuffer buf = (ChannelBuffer) msg;
        
        String imei = String.format("%015d", buf.readLong());
        if (!identify(imei)) {
            return null;
        }

        List<Position> positions = new LinkedList<Position>();

        while (buf.readableBytes() > 1) {

            int dataEnd = buf.readUnsignedShort() + buf.readerIndex();
            int type = buf.readUnsignedByte();
            int confirmKey = buf.readUnsignedByte() & 0x7F;

            while (buf.readerIndex() < dataEnd) {

                Position position = new Position();
                position.setProtocol(getProtocol());
                position.setDeviceId(getDeviceId());

                int structEnd = buf.readUnsignedByte() + buf.readerIndex();

                long time = buf.readUnsignedInt();
                if ((time & 0x0f) == DATA_TYPE) {

                    time = time >> 4 << 1;
                    time += 0x47798280; // 01/01/2008
                    position.setTime(new Date(time * 1000));

                    // Read masks
                    int mask;
                    List<Integer> masks = new LinkedList<Integer>();
                    do {
                        mask = buf.readUnsignedShort();
                        masks.add(mask);
                    } while (checkBit(mask, 15));

                    mask = masks.get(0);

                    if (checkBit(mask, 0)) {
                        position.setValid(true);
                        position.setLongitude(buf.readFloat());
                        position.setLatitude(buf.readFloat());
                        position.setSpeed(buf.readUnsignedByte());

                        int gps = buf.readUnsignedByte();
                        position.set(Event.KEY_SATELLITES, gps & 0xf);
                        position.set(Event.KEY_HDOP, gps >> 4);

                        position.setCourse(buf.readUnsignedByte());
                        position.setAltitude(buf.readUnsignedShort());

                        position.set(Event.KEY_ODOMETER, buf.readUnsignedInt());
                    }

                    if (checkBit(mask, 1)) {
                        position.set(Event.KEY_INPUT, buf.readUnsignedShort());
                    }

                    for (int i = 1; i <= 8; i++) {
                        if (checkBit(mask, i + 1)) {
                            position.set(Event.PREFIX_ADC + i, buf.readUnsignedShort());
                        }
                    }

                    if (checkBit(mask, 10)) buf.skipBytes(4);
                    if (checkBit(mask, 11)) buf.skipBytes(4);
                    if (checkBit(mask, 12)) buf.skipBytes(2);
                    if (checkBit(mask, 13)) buf.skipBytes(2);

                    if (checkBit(mask, 14)) {
                        position.set(Event.KEY_MCC, buf.readUnsignedShort());
                        position.set(Event.KEY_MNC, buf.readUnsignedByte());
                        position.set(Event.KEY_LAC, buf.readUnsignedShort());
                        position.set(Event.KEY_CELL, buf.readUnsignedShort());
                        position.set(Event.KEY_GSM, buf.readUnsignedByte());
                        buf.readUnsignedByte();
                    }

                    if (checkBit(mask, 0)) {
                        positions.add(position);
                    }
                }

                buf.readerIndex(structEnd);
            }

            // Send response
            if (type == MSG_ASYNC_STACK && channel != null) {
                ChannelBuffer response = ChannelBuffers.buffer(ByteOrder.LITTLE_ENDIAN, 8 + 2 + 2 + 1);
                response.writeLong(Long.valueOf(imei));
                response.writeShort(2);
                response.writeByte(MSG_STACK_COFIRM);
                response.writeByte(confirmKey);

                int checksum = 0;
                for (int i = 0; i < response.writerIndex(); i++) {
                    checksum += response.getUnsignedByte(i);
                }
                response.writeByte(checksum);

                channel.write(response);
            }
        }

        return positions;
    }

}
