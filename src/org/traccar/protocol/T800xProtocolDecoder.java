package org.traccar.protocol;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.traccar.BaseProtocolDecoder;
import org.traccar.database.DataManager;
import org.traccar.helper.ChannelBufferTools;
import org.traccar.helper.Log;
import org.traccar.model.Position;

import javax.xml.bind.DatatypeConverter;
import java.net.SocketAddress;
import java.text.SimpleDateFormat;
import java.util.*;

public class T800xProtocolDecoder extends BaseProtocolDecoder {

    public T800xProtocolDecoder(T800xProtocol protocol) {
        super(protocol);
    }

    SimpleDateFormat dateFormatGmt = new SimpleDateFormat("yyyy-MMM-dd HH:mm:ss");
    SimpleDateFormat dateFormatLocal = new SimpleDateFormat("yyyy-MMM-dd HH:mm:ss");

    private static final int MSG_LOGIN = 0x01;
    private static final int MSG_GPS = 0x02;
    private static final int MSG_HEARTBEAT = 0x03;
    private static final int MSG_ALARM = 0x04;
    private static final int MSG_STATE = 0x05;
    private static final int MSG_SMS = 0x06;
    private static final int MSG_OBD = 0x07;
    private static final int MSG_INTERACTIVE = 0x80;
    private static final int MSG_DATA = 0x81;

    private Long deviceId;

    private void sendLoginResponse(Channel channel, int type, int index, String imei) {
        if (channel != null) {
            int length=7+(imei.length()/2+1);
            ChannelBuffer response = ChannelBuffers.buffer(length);
            response.writeByte(0x23);
            response.writeByte(0x23); // header
            response.writeByte(type);
            response.writeByte(0x00);
            response.writeByte(length); // length
            response.writeShort(0x0001);//imei
            //IMEI
            response.writeBytes(DatatypeConverter.parseHexBinary("0" + imei));
            channel.write(response);
        }
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ChannelBuffer buf = (ChannelBuffer) msg;

        List<Position> positions=new ArrayList<Position>();
        //List<Status> statusList=new ArrayList<Status>();
        int i=0;
         while (buf.readable()){
             i++;
             buf.skipBytes(2); // header
             int type = buf.readUnsignedByte(); // type
             buf.readShort(); // length
             int index = buf.readUnsignedShort();   // index
             String imei = ChannelBuffers.hexDump(buf.readBytes(8)).substring(1); // imei

             if (type == MSG_LOGIN) {
                 if (identify(imei, channel)) {
                     sendLoginResponse(channel, type, index, imei);  // send login response
                 }
                 return null;
             } else if (deviceId != null &&
                     (type == MSG_GPS || type == MSG_ALARM)) {

                 //ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
                 //extendedInfo.set("index", index);

                 //ACC ON Interval TODO
                 buf.skipBytes(2);
                 //Acc OFF Interval TODO
                 buf.skipBytes(2);

                 //Angel Degree TODO
                 buf.skipBytes(1);

                 //Angel Distance TODO
                 buf.skipBytes(2);

                 //Speed Alarm TODO
                 buf.skipBytes(2);

                 //GPS Status TODO
                 String locationType = getBits(buf.readUnsignedByte());

                 //Gsensor Manager TODO
                 buf.skipBytes(1);


                 //Reserve Bit
                 buf.skipBytes(1);

                 //HeartBit
                 buf.skipBytes(1);

                 //Relay Status
                 buf.skipBytes(1);

                 //Drag Alarm Setting
                 buf.skipBytes(2);

                 // Digital I/O Status
                 String binaryDigitalIO = getBits(buf.readUnsignedByte());
                 //extendedInfo.set("acc", binaryDigitalIO.charAt(1));
                 //extendedInfo.set("ac", binaryDigitalIO.charAt(2));
                 buf.skipBytes(1);

                 // 2 Analog Input
                 buf.skipBytes(4);

                 // Alarm Data
                 String alarmData = ChannelBuffers.hexDump(buf.readBytes(1));
                 String statusType=getStatusType(alarmData);
                 //extendedInfo.set(statusType,true);
                 if(type == MSG_ALARM)
                 {
                     Log.debug("ALARM : "+statusType);
                     if(alarmData.equals("08") || alarmData.equals("10")){
                       sendAlarmPacketAfter16Sec(channel,type,index, imei + alarmData);
                     }else{
                         sendLoginResponse(channel, type, index, imei + alarmData);
                     }
                 }

                 // Reserve
                 buf.skipBytes(1);

                 // Mileage
                 buf.skipBytes(4);


                 // Inner Battery Voltage
                 double batteryVoltage = Double.parseDouble(ChannelBuffers.hexDump(buf.readBytes(1)));
                 //extendedInfo.set("power", getBatteryPerc(batteryVoltage));

                 // Time & Date Calculation
                 Calendar time = Calendar.getInstance();
                 time.clear();
                 time.set(Calendar.YEAR, 2000 + Integer.parseInt(ChannelBuffers.hexDump(buf.readBytes(1))));
                 time.set(Calendar.MONTH, (Integer.parseInt(ChannelBuffers.hexDump(buf.readBytes(1))) - 1));
                 time.set(Calendar.DAY_OF_MONTH, Integer.parseInt(ChannelBuffers.hexDump(buf.readBytes(1))));
                 time.set(Calendar.HOUR_OF_DAY, Integer.parseInt(ChannelBuffers.hexDump(buf.readBytes(1))));
                 time.set(Calendar.MINUTE, Integer.parseInt(ChannelBuffers.hexDump(buf.readBytes(1))));
                 time.set(Calendar.SECOND, Integer.parseInt(ChannelBuffers.hexDump(buf.readBytes(1))));

                 if (locationType.charAt(1) == '1') {
                     Log.debug("GPS DATA");
                     Position position = new Position();
                     position.setDeviceId(deviceId);
                     position.setTime(time.getTime());

                     // Read Altitude
                     byte[] altitude = new byte[]{buf.readByte(), buf.readByte(), buf.readByte(), buf.readByte()};
                     position.setAltitude((double) bytes2Float(altitude, 0));

                     // Read Longitude & Latitude
                     byte[] longitude = new byte[]{buf.readByte(), buf.readByte(), buf.readByte(), buf.readByte()};
                     byte[] latitude = new byte[]{buf.readByte(), buf.readByte(), buf.readByte(), buf.readByte()};
                     position.setLongitude((double) bytes2Float(longitude, 0));
                     position.setLatitude((double) bytes2Float(latitude, 0));

                     // Read Speed
                     String str_speed = ChannelBuffers.hexDump(buf.readBytes(2));
                     String result = str_speed.substring(0, 3) + "." + str_speed.substring(3);
                     position.setSpeed(Double.parseDouble(result) * 0.539957);

                     //position.setExtendedInfo(extendedInfo.toString());
                     positions.add(position);

                     buf.skipBytes(2);
                 }
                 else {
                     Log.debug("LBS DATA");
                     if(type == MSG_ALARM)
                     {
                         /*Status status = new Status();
                         status.setDate(time.getTime());
                         status.setDeviceid(deviceId);
                         status.setVoltage(getBatteryPerc(batteryVoltage));
                         status.setStatusType(statusType);
                         statusList.add(status);*/
                     }
                     buf.skipBytes(16);
                 }
             } else if (deviceId != null &&
                     (type == MSG_HEARTBEAT)) {
                 dateFormatGmt.setTimeZone(TimeZone.getTimeZone("GMT"));
                 /*Status status = new Status();
                 status.setDate(dateFormatLocal.parse(dateFormatGmt.format(new Date())));
                 status.setDeviceid(deviceId);
                 statusList.add(status);*/
                 sendLoginResponse(channel, type, index, imei);
             }
         }

        /*MixPacket mixPacket=new MixPacket();
        mixPacket.setPositions(positions);
        mixPacket.setStatus(statusList);*/
        return positions;
    }

    private void sendAlarmPacketAfter16Sec(final Channel channel,final int type,final int index,final String alarmData) {
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                sendLoginResponse(channel, type, index,alarmData);
            }
        }, 16001);
    }


    private static String getBits(int flags) {
        int hexFlag = Integer.parseInt(Integer.toHexString(flags), 16);
        String binaryAlarm = Integer.toBinaryString(hexFlag);
        binaryAlarm = (String.format("%8s", binaryAlarm)).replace(' ', '0');
        return binaryAlarm;
    }

    public static float bytes2Float(byte[] bytes, int offset) {
        int value;
        value = bytes[offset];
        value &= 0xff;
        value |= ((long) bytes[offset + 1] << 8);
        value &= 0xffff;
        value |= ((long) bytes[offset + 2] << 16);
        value &= 0xffffff;
        value |= ((long) bytes[offset + 3] << 24);

        return Float.intBitsToFloat(value);
    }

    public static int getBatteryPerc(double batteryPercentage) {
        if (batteryPercentage >= 1 && batteryPercentage < 6) {
            return 0;
        } else if (batteryPercentage > 5 && batteryPercentage < 11) {
            return 1;
        } else if (batteryPercentage > 10 && batteryPercentage < 21) {
            return 2;
        } else if (batteryPercentage > 20 && batteryPercentage < 36) {
            return 3;
        } else if (batteryPercentage > 35 && batteryPercentage < 71) {
            return 4;
        } else if (batteryPercentage > 70 && batteryPercentage < 96) {
            return 5;
        } else if (batteryPercentage > 95 && batteryPercentage < 101 || batteryPercentage == 0) {
            return 6;
        }
        return -1;
    }

    private String getStatusType(String alarmCode) {
        String alarmCodeString = "";
        if (alarmCode.equals("02")) {
            alarmCodeString = "LBA";
        }else if (alarmCode.equals("03")) {
            alarmCodeString = "SOS";
        }else if (alarmCode.equals("08") || alarmCode.equals("10") ) {
            alarmCodeString = "VIBALM";
        } else if (alarmCode.equals("16")) {
            alarmCodeString = "PWROFF";
        } else if (alarmCode.equals("15")) {
            alarmCodeString = "PWRON";
        } else {
            alarmCodeString = "NDFND";
        }
        return alarmCodeString;
    }

}