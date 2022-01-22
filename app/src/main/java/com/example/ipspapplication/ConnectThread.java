package com.example.ipspapplication;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class ConnectThread extends Thread {

    private final String TAG_CONNECT = "CONN-DeviceTryToConnect";
    private final String TAG_CONN_SETUP = "CONN-SETUP-6LoWpanConnectionSetup";
    private final String TAG_TIME = "TIME";
    private final String TAG_NHC = "NHC"; //Next Header Compression
    private final String TAG_ROUTING = "ROUTING";
    private final String TAG_EREQU = "ER"; //Echo Request
    private final String TAG_ERESP = "ECHO_RESP"; //Echo Response

    private BluetoothDevice mSelectedDevice;
    private BluetoothSocket mL2capCocBluetoothSocket;

    private byte[] linkLocalRouterAddress;
    private byte[] linkLocalPeripheralAddress;
    private byte[] globalRouterAddress;
    private byte[] globalPeripheralAddress;
    private byte[] ipv6DestinationAddress;

    private byte[] allNodesMulticastAddress;
    private byte[] allRoutersMulticastAddress;

    private byte[] ownDeviceAddress;
    private byte[] manualDeviceAddressBuffer; //Buffer to read in manual BDA for pinging
    private byte[] selectedDeviceAddress;
    private byte[] prefix;

    private boolean routerSolicitationReceived = false;

    public Activity activity;
    public boolean fullCompression = false;
    public long timespan = 0;

    /*
    class IPHC {
        public int TF; //0, 1, 2, 3
        public boolean NH;
        public int HLIM;
        public boolean CID;
        public boolean SAC;
        public int SAM;
        public boolean M;
        public boolean DAC;
        public int DAM;

        public byte nextHeader;
        public byte[] sourceAddress;
        public byte[] destinationAddress;

        public boolean isNS;
        public byte[] targetAddress;
        public boolean NHChecksum;
        public int NHPorts;

        public byte[] udpSP;
        public byte[] udpDP;
    }
    */

    public ConnectThread(Context context, BluetoothDevice device) {
        activity = (Activity) context;

        mSelectedDevice = device;
        linkLocalPeripheralAddress = new byte[16];
        linkLocalRouterAddress = new byte[16];
        Arrays.fill(linkLocalPeripheralAddress, (byte)0);
        Arrays.fill(linkLocalRouterAddress, (byte)0);

        manualDeviceAddressBuffer = new byte[6];
        String[] bdaOwnDevice = BluetoothAdapter.getDefaultAdapter().getAddress().split(":");
        ownDeviceAddress = new byte[6];
        for (int i = 0; i < bdaOwnDevice.length; i++)
            ownDeviceAddress[i] = Integer.decode("0x" + bdaOwnDevice[i]).byteValue();

        //for debugging or when errors occur while reading own BDA its possible to set manually address of the peripheral device
        ownDeviceAddress[0] = (byte) 0x78;
        ownDeviceAddress[1] = (byte) 0xf8;
        ownDeviceAddress[2] = (byte) 0x82;
        ownDeviceAddress[3] = (byte) 0x52;
        ownDeviceAddress[4] = (byte) 0x5f;
        ownDeviceAddress[5] = (byte) 0x3f;
        Log.d("ODA", "own device address: " + bytesToHex(ownDeviceAddress));

        setLinkLocalPrefix(linkLocalRouterAddress);
        linkLocalRouterAddress[11] = (byte) 0xff;
        linkLocalRouterAddress[12] = (byte) 0xfe;
        for (int i = 0; i < 3; i++) {
            linkLocalRouterAddress[i+8] = ownDeviceAddress[i];
            linkLocalRouterAddress[i+13] = ownDeviceAddress[i+3];
        }
        Log.d("ODA", "link local router address: " + bytesToHex(linkLocalRouterAddress));

        globalPeripheralAddress = new byte[16];
        globalRouterAddress = new byte[16];
        Arrays.fill(globalPeripheralAddress, (byte)0);
        Arrays.fill(globalRouterAddress, (byte)0);

        ipv6DestinationAddress = new byte[16];
        Arrays.fill(ipv6DestinationAddress, (byte)0);

        allNodesMulticastAddress = new byte[16];
        allRoutersMulticastAddress = new byte[16];
        Arrays.fill(allNodesMulticastAddress, (byte)0);
        Arrays.fill(allRoutersMulticastAddress, (byte)0);

        allNodesMulticastAddress[0] = (byte) 0xff;
        allNodesMulticastAddress[1] = (byte) 0x02;
        allNodesMulticastAddress[15] = (byte) 0x01;
        allRoutersMulticastAddress[0] = (byte) 0xff;
        allRoutersMulticastAddress[1] = (byte) 0x02;
        allRoutersMulticastAddress[15] = (byte) 0x02;

        prefix = new byte[8];
        Arrays.fill(prefix, (byte)0);
        //setPrefix();

        selectedDeviceAddress = new byte[6]; //BDA
        Arrays.fill(selectedDeviceAddress, (byte)0);
        String[] bdaSelectedDevice = mSelectedDevice.getAddress().split(":");
        for (int i = 0; i < bdaSelectedDevice.length; i++)
            selectedDeviceAddress[i] = Integer.decode("0x" + bdaSelectedDevice[i]).byteValue();

    }

    public void run() {
        connectThroughL2capCoc();
    }

    private void connectThroughL2capCoc() {
        //Try to create a BluetoothSocket and connect to a remote CoC device
        mL2capCocBluetoothSocket = null;
        BluetoothDevice dev = mSelectedDevice;
        BluetoothSocket tmp = null;

        Log.d(TAG_CONNECT, "remote device - address: " + dev.getAddress());

        try {
            Method m = dev.getClass().getMethod("createInsecureL2capSocket", new Class[]{int.class}); //Android 8
            //Method m = dev.getClass().getMethod("createL2capSocket", new Class[]{int.class}); //Android 7
            Log.d(TAG_CONNECT, "get method createL2capSocket() successfully, type: " + m.getName());
            tmp = (BluetoothSocket) m.invoke(dev, 0x20023);// 0x0023 for IPSP (Android8: 0x20023), 0x20025 (OTP)
            Log.d(TAG_CONNECT, "called method createL2capSocket() successfully, type: " + tmp.toString());

        } catch (NoSuchMethodException e) {
            Log.e(TAG_CONNECT, "No such method createL2capSocket()", e);
        } catch (InvocationTargetException e) {
            Log.e(TAG_CONNECT, "Invocation target exception", e);
        } catch (IllegalAccessException e) {
            Log.e(TAG_CONNECT, "Illegal access exception", e);
        }

        mL2capCocBluetoothSocket = tmp;

        if (mL2capCocBluetoothSocket != null) {
            Log.d(TAG_CONNECT, "connection type: " + mL2capCocBluetoothSocket.getConnectionType());
            Log.d(TAG_CONNECT, "is connected: " + mL2capCocBluetoothSocket.isConnected());
            Log.d(TAG_CONNECT, "connected device: " + mL2capCocBluetoothSocket.getRemoteDevice().getName() + " address: " +
                    mL2capCocBluetoothSocket.getRemoteDevice().getAddress() + " type: " + mL2capCocBluetoothSocket.getRemoteDevice().getType());
            Log.d(TAG_CONNECT, "getMaxReceivePacketSize: " + mL2capCocBluetoothSocket.getMaxReceivePacketSize());
            Log.d(TAG_CONNECT, "getMaxTransmitPacketSize: " + mL2capCocBluetoothSocket.getMaxTransmitPacketSize());
        } else
            Log.e(TAG_CONNECT, "mL2capCocBluetoothSocket IS null");

        try {
            mL2capCocBluetoothSocket.connect();
        } catch (IOException e) {
            Log.e(TAG_CONNECT, "IO Exception when try to connect to BluetoothDevice: ", e);
        }

        if (mL2capCocBluetoothSocket.isConnected()) {
            Log.d(TAG_CONN_SETUP, "connected TRUE");
            Log.d(TAG_CONNECT, "getMaxReceivePacketSize: " + mL2capCocBluetoothSocket.getMaxReceivePacketSize());
            Log.d(TAG_CONNECT, "getMaxTransmitPacketSize: " + mL2capCocBluetoothSocket.getMaxTransmitPacketSize());

        } else {
            Log.d(TAG_CONN_SETUP, "connected FALSE");

            int counter = 0;

            while (counter < 20) {
                try {
                    mL2capCocBluetoothSocket.connect();
                } catch (IOException e) {
                    Log.e(TAG_CONNECT, "Socket's connect() method failed", e);
                }

                if (mL2capCocBluetoothSocket.isConnected()) {
                    Log.d(TAG_CONNECT, "Socket's accept() method success!!!");
                    break;
                }

                try {
                    Thread.sleep(100);
                } catch (Exception e) {
                    Log.e(TAG_CONNECT, e.toString());
                }

                counter++;
            }
        }

        //---------------------------------------------------------------------------------
        // start to receive messages

        int size = 600;
        byte[] buf = new byte[size];
        Arrays.fill(buf, (byte) 0);

        int readCounter = 0;

        while (true) {

            if (readCounter > 0 && routerSolicitationReceived == false) {
                Log.d(TAG_CONNECT, "send manual RA message");
                sendMessage(getRouterAdvertisement());
            }

            Arrays.fill(buf, (byte) 0);
            int ret = 0;
            try {
                Log.d(TAG_CONNECT, "+++getMaxReceivePacketSize: " + mL2capCocBluetoothSocket.getMaxReceivePacketSize());
                Log.d(TAG_CONNECT, "+++getMaxTransmitPacketSize: " + mL2capCocBluetoothSocket.getMaxTransmitPacketSize());

                timespan = System.currentTimeMillis();
                ret = mL2capCocBluetoothSocket.getInputStream().read(buf);

                long diff = System.currentTimeMillis() - timespan;
                Log.d(TAG_TIME, "needed Time in ms for responding to udp echo request after read Buffer: " + diff);
            } catch (Exception e) {
                Log.e("###READ", "exception at reading input stream: " + e.getMessage());
            }
            Log.d(TAG_CONN_SETUP, "run: " + readCounter + " bytes read: " + ret);

            long difference = System.currentTimeMillis() - timespan;
            Log.d(TAG_TIME, "needed Time in ms for responding to udp echo request after read inputstream: " + difference);

            if (ret < 1)
                break;
            Log.d("###REG", "ret is: " + ret);
            byte[] bufSmall = new byte[ret];
            for (int i = 0; i < ret; i++)
                bufSmall[i] = buf[i];

            Log.d(TAG_CONN_SETUP, "size: " + ret + "; connectedDevice: " + mSelectedDevice.getAddress() + " bytes to hex: " + bytesToHex(bufSmall));
            IPHC compressedHeader = new IPHC();
            difference = System.currentTimeMillis() - timespan;
            Log.d(TAG_TIME, "needed Time in ms for responding to udp echo request before decompress: " + difference);

            compressedHeader.decompressIPHC(bufSmall);
            int bytePos = analyzeMessageHeader(bufSmall, compressedHeader);

            Log.d("###TYPE", "dest address of message: " + bytesToHex(compressedHeader.destinationAddress));
            Log.d("###TYPE", "src address of message: " + bytesToHex(compressedHeader.sourceAddress));
            Log.d("###TYPE", "link local router address: " + bytesToHex(linkLocalRouterAddress));
            Log.d("###TYPE", "global router address: " + bytesToHex(globalRouterAddress));

            if (!Arrays.equals(compressedHeader.destinationAddress, linkLocalRouterAddress) || Arrays.equals(compressedHeader.destinationAddress, globalRouterAddress)) {
                Log.d("###ROUTING", "message must be routed");

                //if dest address available in thread-list, skip rest inside this while-loop
                if (routeMessage(insertRealSourceAddress(bufSmall, compressedHeader), compressedHeader.destinationAddress)) {
                    Log.d("###ROUTING", "routed message: " + bytesToHex(bufSmall));
                    Log.d("###ROUTING", "routed is true");
                    continue;
                }
            }

            if (!compressedHeader.NH) {
                if (compressedHeader.nextHeader == (byte) 0x3A) {
                    Log.d("###TYPE", "message type is ICMPv6 (0x3A)");
                    analyzeMessageContent(bufSmall, compressedHeader, bytePos);

                } else if (compressedHeader.nextHeader == (byte) 0x00) {
                    Log.d("###TYPE", "message type is IPv6 Hop-by-Hop Option and doesn't get processed");
                }
                else {
                    Log.d("###TYPE", "message type is " + byteToHex(compressedHeader.nextHeader));
                }
            } else {
                Log.d("###TYPE", "next header has to get decompressed");
                Log.d("###TYPE", "dest address of UDP packet: " + bytesToHex(compressedHeader.destinationAddress));

                if (Arrays.equals(compressedHeader.destinationAddress, linkLocalRouterAddress) || Arrays.equals(compressedHeader.destinationAddress, globalRouterAddress)) {
                    Log.d("###ROUTE", "dest address is own address");
                    decompressNH(bufSmall, compressedHeader, bytePos);
                } else {
                    Log.d("###ROUTE", "dest address is not own address, it is: " + bytesToHex(compressedHeader.destinationAddress) + " size: " + bufSmall.length);

                    // routing should already happen before
                }

                long diff = System.currentTimeMillis() - timespan;
                Log.d(TAG_TIME, "needed Time in ms for responding to udp echo request before decompressNH called: " + diff);
            }

            readCounter++;
        }

        Log.d("###", "size of open connections: " + ((MainActivity)this.activity).bluetoothConnections.size());

    }

    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    private int analyzeMessageHeader(byte[] inputMessage, IPHC compressedHeader) {

        int bytePosition = 2;
        byte hopLimit = 0x0;

        switch (compressedHeader.TF) {
            case 0:
                bytePosition += 4;
                break;
            case 1:
                bytePosition += 3;
                break;
            case 2:
                bytePosition += 1;
                break;
        }

        if (!compressedHeader.NH) {
            compressedHeader.nextHeader = inputMessage[bytePosition];
            bytePosition += 1;
        }

        switch (compressedHeader.HLIM) {
            case 0:
                hopLimit = inputMessage[bytePosition];
                bytePosition += 1;
                break;
            case 1:
                hopLimit = (byte)0x01; //dez 1
                break;
            case 2:
                hopLimit = (byte)0x40; //dez 64
                break;
            case 3:
                hopLimit = (byte)0xff; //dez 255
                break;
        }

        if (!compressedHeader.SAC) {
            switch (compressedHeader.SAM) {
                case 0:
                    for (int i = 0; i < 16; i++) {
                        linkLocalPeripheralAddress[i] = inputMessage[bytePosition];
                        bytePosition++;
                    }
                    compressedHeader.sourceAddress = linkLocalPeripheralAddress;
                    break;
                case 1:
                    setLinkLocalPrefix(linkLocalPeripheralAddress);
                    for (int i = 0; i < 8; i++) {
                        linkLocalPeripheralAddress[i+8] = inputMessage[bytePosition];
                        bytePosition++;
                    }
                    compressedHeader.sourceAddress = linkLocalPeripheralAddress;
                    break;
                case 2:
                    setLinkLocalPrefix(linkLocalPeripheralAddress);
                    setPadding000fffe0(linkLocalPeripheralAddress);
                    linkLocalPeripheralAddress[14] = inputMessage[bytePosition];
                    linkLocalPeripheralAddress[15] = inputMessage[bytePosition+1];
                    bytePosition += 2;
                    compressedHeader.sourceAddress = linkLocalPeripheralAddress;
                    break;
                case 3:
                    setLinkLocalPrefix(linkLocalPeripheralAddress);
                    setSelectedDeviceAddressWithffee(linkLocalPeripheralAddress);
                    compressedHeader.sourceAddress = linkLocalPeripheralAddress;
                    break;
            }
        } else {
            switch (compressedHeader.SAM) {
                case 0:
                    Arrays.fill(linkLocalPeripheralAddress, (byte)0);
                    compressedHeader.sourceAddress = linkLocalPeripheralAddress;
                    break;
                case 1:
                    for (int i = 0; i < 8; i++) {
                        linkLocalPeripheralAddress[i+8] = inputMessage[bytePosition];
                        bytePosition++;
                    }
                    compressedHeader.sourceAddress = linkLocalPeripheralAddress;
                    break;
                case 2:
                    setPadding000fffe0(linkLocalPeripheralAddress);
                    linkLocalPeripheralAddress[14] = inputMessage[bytePosition];
                    linkLocalPeripheralAddress[15] = inputMessage[bytePosition+1];
                    bytePosition += 2;
                    compressedHeader.sourceAddress = linkLocalPeripheralAddress;
                    break;
                case 3:
                    setSelectedDeviceAddressWithffee(linkLocalPeripheralAddress);
                    compressedHeader.sourceAddress = linkLocalPeripheralAddress;
                    break;
            }
        }

        if (!compressedHeader.M && !compressedHeader.DAC) {
            switch (compressedHeader.DAM) {
                case 0:
                    for (int i = 0; i < 16; i++) {
                        ipv6DestinationAddress[i] = inputMessage[bytePosition];
                        bytePosition++;
                    }
                    break;
                case 1:
                    setLinkLocalPrefix(ipv6DestinationAddress);
                    for (int i = 0; i < 8; i++) {
                        ipv6DestinationAddress[i+8] = inputMessage[bytePosition];
                        bytePosition++;
                    }
                    break;
                case 2:
                    setLinkLocalPrefix(ipv6DestinationAddress);
                    setPadding000fffe0(ipv6DestinationAddress);
                    ipv6DestinationAddress[14] = inputMessage[bytePosition];
                    ipv6DestinationAddress[15] = inputMessage[bytePosition+1];
                    bytePosition += 2;
                    break;
                case 3:
                    setLinkLocalPrefix(ipv6DestinationAddress);
                    setOwnDeviceAddressWithffee(ipv6DestinationAddress);
                    break;
            }
        } else if (!compressedHeader.M && compressedHeader.DAC) {
            switch (compressedHeader.DAM) {
                //case 0: RESERVED
                case 1:
                    for (int i = 0; i < 8; i++) {
                        ipv6DestinationAddress[i+8] = inputMessage[bytePosition];
                        bytePosition++;
                    }
                    break;
                case 2:
                    setPadding000fffe0(ipv6DestinationAddress);
                    ipv6DestinationAddress[14] = inputMessage[bytePosition];
                    ipv6DestinationAddress[15] = inputMessage[bytePosition+1];
                    bytePosition += 2;
                    break;
                case 3:
                    break;
            }
        } else if (compressedHeader.M && !compressedHeader.DAC) {
            switch (compressedHeader.DAM) {
                case 0:
                    for (int i = 0; i < 16; i++) {
                        ipv6DestinationAddress[i] = inputMessage[bytePosition];
                        bytePosition++;
                    }
                    break;
                case 1:
                    Arrays.fill(ipv6DestinationAddress, (byte)0);
                    ipv6DestinationAddress[0] = (byte)0xff;
                    ipv6DestinationAddress[1] = inputMessage[bytePosition];
                    bytePosition++;
                    for (int i = 0; i < 5; i++) {
                        ipv6DestinationAddress[i+11] = inputMessage[bytePosition];
                        bytePosition++;
                    }
                    break;
                case 2:
                    Arrays.fill(ipv6DestinationAddress, (byte)0);
                    ipv6DestinationAddress[0] = (byte)0xff;
                    ipv6DestinationAddress[1] = inputMessage[bytePosition];
                    bytePosition++;
                    for (int i = 0; i < 3; i++) {
                        ipv6DestinationAddress[i+13] = inputMessage[bytePosition];
                        bytePosition++;
                    }
                    break;
                case 3:
                    Arrays.fill(ipv6DestinationAddress, (byte)0);
                    ipv6DestinationAddress[0] = (byte)0xff;
                    ipv6DestinationAddress[1] = (byte)0x02;
                    ipv6DestinationAddress[15] = inputMessage[bytePosition];
                    bytePosition++;
                    break;
            }
        } else if (compressedHeader.M && compressedHeader.DAC) {
            switch (compressedHeader.DAM) {
                case 0:
                    Arrays.fill(ipv6DestinationAddress, (byte)0);
                    ipv6DestinationAddress[0] = (byte)0xff;
                    ipv6DestinationAddress[1] = inputMessage[bytePosition];
                    bytePosition++;
                    ipv6DestinationAddress[2] = inputMessage[bytePosition];
                    bytePosition++;

                    ipv6DestinationAddress[12] = inputMessage[bytePosition];
                    bytePosition++;
                    ipv6DestinationAddress[13] = inputMessage[bytePosition];
                    bytePosition++;
                    ipv6DestinationAddress[14] = inputMessage[bytePosition];
                    bytePosition++;
                    ipv6DestinationAddress[15] = inputMessage[bytePosition];
                    bytePosition++;
                    break;
                //case 1: RESERVED
                //case 2: RESERVED
                //case 3: RESERVED
            }
        }
        compressedHeader.destinationAddress = ipv6DestinationAddress;

        Log.d("###ODA", "compressed Header ipv6 source address: " + bytesToHex(compressedHeader.sourceAddress));
        Log.d("###ODA", "compressed Header ipv6 destination address: " + bytesToHex(compressedHeader.destinationAddress));

        return bytePosition;
    }

    private void analyzeMessageContent(byte[] inputMessage, IPHC compressedHeader, int bytePos) {
        Log.d("###ANALYZE", "analyzeMessageContent start-----------------------------");

        if (compressedHeader.nextHeader == (byte)0x3A) {
            byte type = inputMessage[bytePos];
            if (type == (byte) 0x85) {
                Log.d("###TYPE", "ICMPv6 message is a Router Solicitation (0x85)");
                routerSolicitationReceived = true;
                analyzeRouterSolicitation(inputMessage, bytePos);
                sendMessage(getRouterAdvertisement());
            } else if (type == (byte) 0x87) {
                Log.d("###TYPE", "ICMPv6 message is a Neighbor Solicitation (0x87)");
                analyzeNeighborSolicitation(inputMessage, bytePos, compressedHeader);
                Log.d("###BDA", BluetoothAdapter.getDefaultAdapter().getAddress());
                sendMessage(getNeighborAdvertisement(compressedHeader));
            } else if (type == (byte)0x80) { //0x80 is dez128 -> type Echo Request
                Log.d("###ANALYZE", "ICMPv6 message is a Echo Request (0x80) - Ping");
                byte[] identifier = new byte[2];
                byte[] sequenceNumber = new byte[2];
                byte[] data = new byte[1];
                analyzeEchoRequest(bytePos, inputMessage, identifier, sequenceNumber, data);
                sendMessage(getIcmpv6EchoReply(compressedHeader, identifier, sequenceNumber, data));
            } else if (type == (byte) 0x81) { // type is Echo Response
                Log.d("###ANALYZE", "ping type: " + byteToHex(type));
                long difference = System.currentTimeMillis() - timespan;
                Log.d(TAG_TIME, "needed time between sending echo request and receiving response: " + difference);
            } else {
                Log.e("###ANALYZE", "type not implemented: " + byteToHex(type));
            }
        } else {
            Log.d("###ANALYZE", "message type is not icmp, it is: " + compressedHeader.nextHeader);
            //routing should happen at another place
        }
        Log.d("###ANALYZE", "analyzeMessageContent end-----------------------------");
    }

    private void sendMessage(byte[] message) {
        try {
            Log.d("###SEND", "----------------------------- send message -----------");
            mL2capCocBluetoothSocket.getOutputStream().write(message);
        } catch (Exception e) {
            Log.e("###EXCEPTION SEND", "Exception at sending message: " + e.getMessage());
        }
    }

    private boolean analyzeRouterSolicitation(byte[] inputMessage, int bytePos) {
        bytePos++;
        if(inputMessage[bytePos] != (byte)0x00) //Code
            return false;

        bytePos += 7; // 2 bytes checksum, 4 bytes reserved

        if (inputMessage.length <= bytePos) //check if inputMessage contains options
            return true;

        checkForIcmpOptions(inputMessage, bytePos);

        return true;
    }

    private boolean analyzeNeighborSolicitation(byte[] inputMessage, int bytePos, IPHC compressedHeader) {
        bytePos++;
        if(inputMessage[bytePos] != (byte)0x00) //Code
            return false;

        bytePos += 7; // 2 bytes checksum, 4 bytes reserved

        //Target Address
        byte[] targetAddress = new byte[16];
        for (int i = 0; i < 16; i++) {
            targetAddress[i] = inputMessage[bytePos];
            bytePos++;
        }

        compressedHeader.isNS = true;
        compressedHeader.targetAddress = targetAddress;

        /*
        Log.d("###ANALYZE", "target address of neighbor solicitation: " + bytesToHex(targetAddress));
        Log.d("###ANALYZE", "link local peripheral address: " + bytesToHex(linkLocalPeripheralAddress));
        Log.d("###ANALYZE", "global peripheral address: " + bytesToHex(globalPeripheralAddress));

        Log.d("###ANALYZE", "link local router address: " + bytesToHex(linkLocalRouterAddress));
        Log.d("###ANALYZE", "global router address: " + bytesToHex(globalRouterAddress));
        */

        if (!Arrays.equals(targetAddress, linkLocalPeripheralAddress) && !Arrays.equals(targetAddress, globalPeripheralAddress)) {
            Log.e("###ANALYZE", "target address doesn't belong to Peripheral, address needs to get updated?");
        } else {
            Log.d("###ANALYZE", "target address is equal as peripheral address (link local or global)");
        }

        if (inputMessage.length <= bytePos) //check if inputMessage contains options
            return true;

        checkForIcmpOptions(inputMessage, bytePos);

        return true;
    }

    private void checkForIcmpOptions(byte[] inputMessage, int bytePos) {
        //SLLAO, type 01
        //TLLAO, type 02

        switch (inputMessage[bytePos]) {
            case((byte)0x01): //SLLAO
                bytePos++;
                if (inputMessage[bytePos] == (byte) 0x01) {
                    setLinkLocalPrefix(linkLocalPeripheralAddress);
                    bytePos++;
                    linkLocalPeripheralAddress[8] = inputMessage[bytePos];
                    bytePos++;
                    linkLocalPeripheralAddress[9] = inputMessage[bytePos];
                    bytePos++;
                    linkLocalPeripheralAddress[10] = inputMessage[bytePos];

                    linkLocalPeripheralAddress[11] = (byte)0xff;
                    linkLocalPeripheralAddress[12] = (byte)0xfe;

                    bytePos++;
                    linkLocalPeripheralAddress[13] = inputMessage[bytePos];
                    bytePos++;
                    linkLocalPeripheralAddress[14] = inputMessage[bytePos];
                    bytePos++;
                    linkLocalPeripheralAddress[15] = inputMessage[bytePos];
                } else {
                    Log.e("###ANALYZE", "SLLAO option has higher length than one: " + inputMessage[bytePos]);
                }

                break;
            case ((byte)0x02): //TLLAO
                byte[] targetLinkLayerAddress = new byte[16];

                bytePos++;
                if (inputMessage[bytePos] == (byte) 0x01) {
                    setLinkLocalPrefix(targetLinkLayerAddress);
                    bytePos++;
                    targetLinkLayerAddress[8] = inputMessage[bytePos];
                    bytePos++;
                    targetLinkLayerAddress[9] = inputMessage[bytePos];
                    bytePos++;
                    targetLinkLayerAddress[10] = inputMessage[bytePos];

                    targetLinkLayerAddress[11] = (byte)0xff;
                    targetLinkLayerAddress[12] = (byte)0xfe;

                    bytePos++;
                    targetLinkLayerAddress[13] = inputMessage[bytePos];
                    bytePos++;
                    targetLinkLayerAddress[14] = inputMessage[bytePos];
                    bytePos++;
                    targetLinkLayerAddress[15] = inputMessage[bytePos];

                } else {
                    Log.e("###ANALYZE", "TLLAO option has higher length than one: " + inputMessage[bytePos]);
                }

                break;
            //case((byte)0x03): //PIO
            //    Log.e("###ANALYZE", "PIO option found but not implemented");
            //    break;
            case((byte)0x021): //ARO
                Log.e("###ANALYZE", "ARO option found but not implemented");
                break;
        }
    }

    private void decompressNH(byte[] wholeMessage, IPHC compressedHeader, int bytePos) {
        //Next Header bit is set to 1 -> use of next header compression
        //message structure: LOWPAN_IPHC | inline IP fields | -> LOWPAN_NHC | inline next header fields | payload

        byte[] sourcePort = new byte[2];
        byte[] destPort = new byte[2];
        byte[] checksum = new byte[2];
        int firstPosition = bytePos;

        Log.d(TAG_NHC, "next bytes of the nhc: " + byteToHex(wholeMessage[bytePos]) + " " + byteToHex(wholeMessage[bytePos+1])+ " " + byteToHex(wholeMessage[bytePos+2]));

        if (getBit(wholeMessage[firstPosition], 7) == 0 || getBit(wholeMessage[firstPosition], 6) == 0 || getBit(wholeMessage[firstPosition], 5) == 0) {
            Log.e(TAG_NHC, "first three bytes has to be 1");
            return;
        }

        if (getBit(wholeMessage[firstPosition], 4) > 0) {
            // UDP Header Compression
            if (getBit(wholeMessage[firstPosition], 3) > 0) {
                Log.e(TAG_NHC, "fifth bit has to be zero");
                return;
            }



            if (getBit(wholeMessage[firstPosition], 1) == 0) { //Port
                if (getBit(wholeMessage[firstPosition], 0) == 0) { //ports 00: 16 bit source-port and 16bit dest-port carried in-line
                    compressedHeader.NHPorts = 0;
                    sourcePort[0] = wholeMessage[bytePos+1];
                    sourcePort[1] = wholeMessage[bytePos+2];
                    destPort[0] = wholeMessage[bytePos+3];
                    destPort[1] = wholeMessage[bytePos+4];

                    Log.d(TAG_NHC, "00 port source: " + byteToHex(wholeMessage[bytePos+1]) + byteToHex(wholeMessage[bytePos+2]) +
                            byteToHex(wholeMessage[bytePos+3]) + byteToHex(wholeMessage[bytePos+4]) +
                            " sourcePort: " + bytesToHex(sourcePort) + " destPort: " + bytesToHex(destPort));

                    bytePos += 5;
                } else {
                    compressedHeader.NHPorts = 1; //ports 01: 16bit source-port and last 8bit dest-port carried in-line
                    sourcePort[0] = wholeMessage[bytePos+1];
                    sourcePort[1] = wholeMessage[bytePos+2];
                    destPort[0] = (byte) 0xf0;
                    destPort[1] = wholeMessage[bytePos+3];

                    Log.d(TAG_NHC, "01 port source: " + byteToHex(wholeMessage[bytePos+1]) + byteToHex(wholeMessage[bytePos+2]) + byteToHex(wholeMessage[bytePos+3]) +
                            " sourcePort: " + bytesToHex(sourcePort) + " destPort: " + bytesToHex(destPort));

                    bytePos += 4;
                }

            } else {
                if (getBit(wholeMessage[firstPosition], 0) == 0) { //ports 10: last 8 bit source-port and 16 bit dest-port carried in-line
                    compressedHeader.NHPorts = 2;
                    sourcePort[0] = (byte) 0xf0;
                    sourcePort[1] = wholeMessage[bytePos+1];
                    destPort[0] = wholeMessage[bytePos+2];
                    destPort[1] = wholeMessage[bytePos+3];

                    Log.d(TAG_NHC, "10 port source: " + byteToHex(wholeMessage[bytePos+1]) + byteToHex(wholeMessage[bytePos+2]) + byteToHex(wholeMessage[bytePos+3]) +
                            " sourcePort: " + bytesToHex(sourcePort) + " destPort: " + bytesToHex(destPort));

                    bytePos += 4;
                } else {
                    compressedHeader.NHPorts = 3; ////ports 11: first 12 bits of source-port and first 12 bits of dest-port are 0xf0b, last 4 bits each are carried in-line
                    sourcePort[0] = (byte) 0xf0;
                    sourcePort[1] = (byte) 0xb0;
                    destPort[0] = (byte) 0xf0;
                    destPort[1] = (byte) 0xb0;

                    byte both = wholeMessage[bytePos+1];

                    if (getBit(both, 7) > 0)
                        sourcePort[1] += 8;
                    if (getBit(both, 6) > 0)
                        sourcePort[1] += 4;
                    if (getBit(both, 5) > 0)
                        sourcePort[1] += 2;
                    if (getBit(both, 4) > 0)
                        sourcePort[1] += 1;

                    if (getBit(both, 3) > 0)
                        destPort[1] += 8;
                    if (getBit(both, 2) > 0)
                        destPort[1] += 4;
                    if (getBit(both, 1) > 0)
                        destPort[1] += 2;
                    if (getBit(both, 0) > 0)
                        destPort[1] += 1;

                    Log.d(TAG_NHC, "11 port source: " + byteToHex(wholeMessage[bytePos+1]) + " sourcePort: " + bytesToHex(sourcePort) + " destPort: " + bytesToHex(destPort));

                    bytePos += 1;
                }
            }

            if (getBit(wholeMessage[firstPosition], 2) > 0) { //checksum 1: elided
                compressedHeader.NHChecksum = true;
            } else { //checksum 0: 16 bits in-line
                compressedHeader.NHChecksum = false;
                checksum[0] = wholeMessage[bytePos];
                checksum[1] = wholeMessage[bytePos+1];
                bytePos += 2;
            }

            compressedHeader.udpSP = sourcePort;
            compressedHeader.udpDP = destPort;

            long difference = System.currentTimeMillis() - timespan;
            Log.d(TAG_TIME, "needed Time in ms for responding to udp echo request before answerUdpEchoRequest called: " + difference);

            Log.d("###", "UDP echo request gets answered");
            answerUdpEchoRequest(wholeMessage, bytePos, compressedHeader);
        } else {
            Log.e(TAG_NHC, "fourth bit is not 1, it is maybe an IPv6 Extension Header");
            return;
        }
    }

    private boolean routeMessage(byte[] message, byte[] targetAddress) {
        Log.d(TAG_ROUTING, "route Message called with target: " + bytesToHex(targetAddress));

        for (ConnectThread thread : ((MainActivity)activity).bluetoothConnections) {
            thread.setLinkLocalPeripheralAddress();

            Log.d(TAG_ROUTING, "thread LL Addr: " + bytesToHex(thread.linkLocalPeripheralAddress) + " and thread global Addr: " + bytesToHex(thread.globalPeripheralAddress));
            Log.d(TAG_ROUTING, "thread ownBDA: " + bytesToHex(thread.selectedDeviceAddress));

            if (Arrays.equals(thread.linkLocalPeripheralAddress, targetAddress) || Arrays.equals(thread.globalPeripheralAddress, targetAddress)) {
                Log.d(TAG_ROUTING, "found thread and send message");
                thread.sendMessage(message);
                return true;
            }
        }

        //TODO: if destination not in routing table for bluetooth-devices, forward message to internet
        return false;
    }

    private byte[] insertRealSourceAddress(byte[] message, IPHC compressedHeader) {
        byte[] newMessage = null;

        if (!((compressedHeader.SAC) && (compressedHeader.SAM == 0))) { //SAC 0 and SAM 00 means full source address
            if (!compressedHeader.SAC && compressedHeader.SAM == 3) { //SAC 0 and SAM 11 means source address fully elided, just insert it
                //adapt SAC and SAM fields
                byte iphc = (byte) 0x0;

                //CID
                if (compressedHeader.CID)
                    iphc = (byte) (iphc | (1 << 7));
                else
                    iphc = (byte) (iphc & ~(1 << 7));

                //SAC
                iphc = (byte) (iphc & ~(1 << 6));
                //SAM (SAC =0, SAM=00: full source-address carried in-line)
                iphc = (byte) (iphc & ~(1 << 5));
                iphc = (byte) (iphc & ~(1 << 4));

                //M
                if (compressedHeader.M)
                    iphc = (byte) (iphc | (1 << 3));
                else
                    iphc = (byte) (iphc & ~(1 << 3));

                //DAC
                if (compressedHeader.DAC)
                    iphc = (byte) (iphc | (1 << 2));
                else
                    iphc = (byte) (iphc & ~(1 << 2));

                //DAM (M=0, DAC=0, if DAM=00: 128 bits the full ipv6 address)
                if (compressedHeader.DAM == 0 || compressedHeader.DAM == 1)
                    iphc = (byte) (iphc & ~(1 << 1));
                else
                    iphc = (byte) (iphc | (1 << 1));

                if (compressedHeader.DAM == 2 || compressedHeader.DAM == 3)
                    iphc = (byte) (iphc & ~(1 << 0));
                else
                    iphc = (byte) (iphc | (1 << 0));

                //--------------------
                //add source address:

                newMessage = new byte[message.length + 16];
                newMessage[0] = message[0];
                newMessage[1] = iphc;

                if (!compressedHeader.NH) {
                    newMessage[2] = message[2];

                    for (int i = 0; i < compressedHeader.sourceAddress.length; i++) {
                        newMessage[3 + i] = compressedHeader.sourceAddress[i];
                    }
                    for (int i = 3; i < message.length; i++) {
                        newMessage[16 + i] = message[i];
                    }
                }
                else {
                    for (int i = 0; i < compressedHeader.sourceAddress.length; i++) {
                        newMessage[2 + i] = compressedHeader.sourceAddress[i];
                    }
                    for (int i = 2; i < message.length; i++) {
                        newMessage[16 + i] = message[i];
                    }
                }
            }
        }

        Log.d(TAG_ROUTING, "length inputt Message: " + message.length);
        if (newMessage != null)
            Log.d(TAG_ROUTING, "length output Message: " + newMessage.length);
        else
            Log.d(TAG_ROUTING, "length output Message: 0");

        if (newMessage != null)
            return newMessage;
        else
            return message;
    }

    private void setLinkLocalPeripheralAddress() {
        setLinkLocalPrefix(linkLocalPeripheralAddress);
        linkLocalPeripheralAddress[8] = selectedDeviceAddress[0];
        linkLocalPeripheralAddress[9] = selectedDeviceAddress[1];
        linkLocalPeripheralAddress[10] = selectedDeviceAddress[2];
        linkLocalPeripheralAddress[11] = (byte) 0xff;
        linkLocalPeripheralAddress[12] = (byte) 0xfe;
        linkLocalPeripheralAddress[13] = selectedDeviceAddress[3];
        linkLocalPeripheralAddress[14] = selectedDeviceAddress[4];
        linkLocalPeripheralAddress[15] = selectedDeviceAddress[5];
    }

    private void answerUdpEchoRequest(byte[] inputMessage, int bytePosContent, IPHC compressedHeader) {
        //create compressed IPHeader
        //create compressed nextHeader UDP
        //copy content from inputMessage, starting with bytePosContent

        byte[] echoResponse = new byte[2];
        Arrays.fill(echoResponse, (byte) 0);
        //IPHC
        byte iphc = (byte) 0;
        iphc = (byte) (iphc & ~(1 << 7));
        iphc = (byte) (iphc | (1 << 6));
        iphc = (byte) (iphc | (1 << 5));
        //TF (Traffic Class and Flow Label)
        iphc = (byte) (iphc | (1 << 4));
        iphc = (byte) (iphc | (1 << 3));
        //NH (Next Header, value 1 -> compressed)
        iphc = (byte) (iphc | (1 << 2));
        //HLIM (Hop Limit Field, value 10 -> 64)
        iphc = (byte) (iphc | (1 << 1));
        iphc = (byte) (iphc & ~(1 << 0));

        echoResponse[0] = iphc;
        iphc = (byte) 0;

        //CID
        iphc = (byte) (iphc & ~(1 << 7));
        //SAC
        iphc = (byte) (iphc & ~(1 << 6));
        //SAM (SAC =0, SAM=00: full source-address carried in-line)
        if (fullCompression) {
            iphc = (byte) (iphc | (1 << 5));
            iphc = (byte) (iphc | (1 << 4));
        } else {
            iphc = (byte) (iphc & ~(1 << 5));
            iphc = (byte) (iphc & ~(1 << 4));
        }
        //M
        iphc = (byte) (iphc & ~(1 << 3));
        //DAC
        iphc = (byte) (iphc & ~(1 << 2));
        //DAM (M=0, DAC=0, if DAM=00: 128 bits the full ipv6 address)
        if (fullCompression) {
            iphc = (byte) (iphc | (1 << 1));
            iphc = (byte) (iphc | (1 << 0));
        } else {
            iphc = (byte) (iphc & ~(1 << 1));
            iphc = (byte) (iphc & ~(1 << 0));
        }

        echoResponse[1] = iphc;

        //+++++++++++++++++++++++++++++++++++++++++++
        // in-line parameters:

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //Source Address:

        byte[] sa = new byte[16];
        Arrays.fill(sa, (byte) 0);
        for (int i = 0; i < 16; i++) {
            sa[i] = compressedHeader.destinationAddress[i];
            //sa[i] = linkLocalRouterAddress[i];
        }

        if (!fullCompression) {
            Log.d(TAG_ERESP, "source address: " + bytesToHex(sa));

            byte[] ra_sa = new byte[echoResponse.length + sa.length];
            System.arraycopy(echoResponse, 0, ra_sa, 0, echoResponse.length);
            System.arraycopy(sa, 0, ra_sa, echoResponse.length, sa.length);
            echoResponse = ra_sa;
        }

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //Destination Address:

        byte[] da = new byte[16];
        Arrays.fill(da, (byte) 0);
        System.arraycopy(linkLocalPeripheralAddress, 0, da, 0, linkLocalPeripheralAddress.length);

        if (!fullCompression) {
            Log.d(TAG_ERESP, "destination address: " + bytesToHex(da));

            byte[] ra_da = new byte[echoResponse.length + da.length];
            System.arraycopy(echoResponse, 0, ra_da, 0, echoResponse.length);
            System.arraycopy(da, 0, ra_da, echoResponse.length, da.length);
            echoResponse = ra_da;
        }

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //UDP compressed header:

        byte[] udpCH = new byte[7];
        Arrays.fill(udpCH, (byte) 0);

        udpCH[0] = (byte) 0xf0;
        udpCH[1] = compressedHeader.udpDP[0];
        udpCH[2] = compressedHeader.udpDP[1];
        udpCH[3] = compressedHeader.udpSP[0];
        udpCH[4] = compressedHeader.udpSP[1];

        int contentSize = inputMessage.length - bytePosContent;
        byte[] content = new byte[contentSize];
        Arrays.fill(content, (byte) 0);
        for (int i = 0; i < contentSize; i++) {
            content[i] = inputMessage[i+bytePosContent];
        }

        Log.d("###CHECKSUM", "whole input message: " + bytesToHex(inputMessage));
        Log.d("###CHECKSUM", "udp content: " + bytesToHex(content));

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //add UDP-pseudoheader for computing the checksum!!!
        byte[] udpPseudoheader = new byte[8];
        Arrays.fill(udpPseudoheader, (byte) 0);

        udpPseudoheader[0] = udpCH[1]; //source Port
        udpPseudoheader[1] = udpCH[2]; //source Port
        udpPseudoheader[2] = udpCH[3]; //destination Port
        udpPseudoheader[3] = udpCH[4]; //destination Port

        udpPseudoheader[4] = (byte) 0; //length
        udpPseudoheader[5] = (byte) (udpPseudoheader.length + content.length); //length

        udpPseudoheader[6] = (byte) 0; //checksum
        udpPseudoheader[7] = (byte) 0; //checksum

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //add IPv6-pseudoheader for computing the checksum!!!
        byte[] ipv6Pseudoheader = new byte[40];
        Arrays.fill(ipv6Pseudoheader, (byte) 0);

        //Source Address 16 byte
        for (int i = 0; i < 16; i++) {
            ipv6Pseudoheader[i] = sa[i];
        }

        //Destination Address 16 byte
        for (int i = 0; i < 16; i++) {
            ipv6Pseudoheader[i + 16] = da[i];
        }

        //test to force an "icmpv6 drop packet beacuse of false checksum" error message
        //ipv6Pseudoheader[20] = (byte) 0x00;
        //ipv6Pseudoheader[21] = (byte) 0x00;

        //Upper-Layer Packet Length (udp packet with content)
        int lengthIcmp = udpPseudoheader.length + content.length;
        ipv6Pseudoheader[35] = (byte) lengthIcmp;

        //zero 3 byte

        //Next Header 1 byte (udp is dez 17 = 0x11)
        ipv6Pseudoheader[39] = (byte) 17;

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        byte[] udpChecksumBuffer = new byte[udpPseudoheader.length + content.length + ipv6Pseudoheader.length];

        System.arraycopy(ipv6Pseudoheader, 0, udpChecksumBuffer, 0, ipv6Pseudoheader.length);
        System.arraycopy(udpPseudoheader, 0, udpChecksumBuffer, ipv6Pseudoheader.length, udpPseudoheader.length);
        System.arraycopy(content, 0, udpChecksumBuffer, ipv6Pseudoheader.length + udpPseudoheader.length, content.length);

        long chcksm = calculateIPChecksum(udpChecksumBuffer);
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putLong(chcksm);
        udpCH[5] = buf.array()[6];
        udpCH[6] = buf.array()[7];

        udpCH[5] = (byte) 0;
        udpCH[6] = (byte) 0;

        byte[] wholeEchoResponse = new byte[echoResponse.length + udpCH.length + content.length];
        System.arraycopy(echoResponse, 0, wholeEchoResponse, 0, echoResponse.length);
        System.arraycopy(udpCH, 0, wholeEchoResponse, echoResponse.length, udpCH.length);
        System.arraycopy(content, 0, wholeEchoResponse, echoResponse.length + udpCH.length, content.length);

        Log.d(TAG_ERESP, "send Echo response of size: " + wholeEchoResponse.length + "  content: " + bytesToHex(wholeEchoResponse));

        long difference = System.currentTimeMillis() - timespan;
        Log.d(TAG_TIME, "needed Time in ms for responding to udp echo request before send: " + difference);
        sendMessage(wholeEchoResponse);

        difference = System.currentTimeMillis() - timespan;
        Log.d(TAG_TIME, "needed Time in ms for responding to udp echo request after send: " + difference);
    }

    private void setLinkLocalPrefix(byte[] address) {
        address[0] = (byte) 0xfe;
        address[1] = (byte) 0x80;
        address[2] = (byte) 0x00;
        address[3] = (byte) 0x00;
        address[4] = (byte) 0x00;
        address[5] = (byte) 0x00;
        address[6] = (byte) 0x00;
        address[7] = (byte) 0x00;
    }

    private void setPersonalPrefix(byte[] address) {
        for (int i = 0; i < prefix.length; i++) {
            address[i] = prefix[i];
        }
    }

    private void setPadding000fffe0(byte[] address) {
        address[8] = (byte) 0x00;
        address[9] = (byte) 0x00;
        address[10] = (byte) 0x00;
        address[11] = (byte) 0xff;
        address[12] = (byte) 0xfe;
        address[13] = (byte) 0x00;
    }

    private void setSelectedDeviceAddressWithffee(byte[] address) {
        address[8] = selectedDeviceAddress[0];
        address[9] = selectedDeviceAddress[1];
        address[10] = selectedDeviceAddress[2];
        address[11] = (byte)0xff;
        address[12] = (byte)0xfe;
        address[13] = selectedDeviceAddress[3];
        address[14] = selectedDeviceAddress[4];
        address[15] = selectedDeviceAddress[5];
    }

    private void setOwnDeviceAddressWithffee(byte[] address) {
        address[8] = ownDeviceAddress[0];
        address[9] = ownDeviceAddress[1];
        address[10] = ownDeviceAddress[2];
        address[11] = (byte)0xff;
        address[12] = (byte)0xfe;
        address[13] = ownDeviceAddress[3];
        address[14] = ownDeviceAddress[4];
        address[15] = ownDeviceAddress[5];
    }

    public int getBit(byte input, int position) {
        int res = ((byte) input) & (0x01 << position);

        return res;
    }

    //Calculate the Internet Checksum (RFC 1071 - http://www.faqs.org/rfcs/rfc1071.html)
    public long calculateIPChecksum(byte[] buf) {
        int length = buf.length;
        int i = 0;

        long sum = 0;
        long data;

        // Handle all pairs
        while (length > 1) {
            // Corrected to include @Andy's edits and various comments on Stack Overflow
            data = (((buf[i] << 8) & 0xFF00) | ((buf[i + 1]) & 0xFF));
            sum += data;
            // 1's complement carry bit correction in 16-bits (detecting sign extension)
            if ((sum & 0xFFFF0000) > 0) {
                sum = sum & 0xFFFF;
                sum += 1;
            }

            i += 2;
            length -= 2;
        }

        // Handle remaining byte in odd length buffers
        if (length > 0) {
            // Corrected to include @Andy's edits and various comments on Stack Overflow
            sum += (buf[i] << 8 & 0xFF00);
            // 1's complement carry bit correction in 16-bits (detecting sign extension)
            if ((sum & 0xFFFF0000) > 0) {
                sum = sum & 0xFFFF;
                sum += 1;
            }
        }

        // Final 1's complement value correction to 16-bits
        sum = ~sum;
        sum = sum & 0xFFFF;
        return sum;
    }

    private byte[] getRouterAdvertisement() {
        byte[] ra = new byte[3];
        Arrays.fill(ra, (byte) 0);

        //IPHC
        byte iphc = (byte) 0;

        //011
        iphc = (byte) (iphc & ~(1 << 7));
        iphc = (byte) (iphc | (1 << 6));
        iphc = (byte) (iphc | (1 << 5));

        //TF (Traffic Class and Flow Label) 11
        iphc = (byte) (iphc | (1 << 4));
        iphc = (byte) (iphc | (1 << 3));

        //NH (Next Header) 0
        iphc = (byte) (iphc & ~(1 << 2));

        //HLIM (Hop Limit Field, value 11 -> 255) 11
        iphc = (byte) (iphc | (1 << 1));
        iphc = (byte) (iphc | (1 << 0));


        ra[0] = iphc;
        iphc = (byte) 0;

        //CID is 0
        iphc = (byte) (iphc & ~(1 << 7));

        //SAC is 0
        iphc = (byte) (iphc & ~(1 << 6));

        //SAM (SAC =0, SAM=01: 64 bits with link-local prefix(fe80:0000:0000:0000))
        if (fullCompression) {
            iphc = (byte) (iphc | (1 << 5));
            iphc = (byte) (iphc | (1 << 4));
        } else {
            iphc = (byte) (iphc & ~(1 << 5));
            iphc = (byte) (iphc | (1 << 4));
        }

        //M
        iphc = (byte) (iphc & ~(1 << 3));

        //DAC
        iphc = (byte) (iphc & ~(1 << 2));

        //DAM (M=0, DAC=0, if DAM=00: 128 bits the full ipv6 address)
        if (fullCompression) {
            iphc = (byte) (iphc | (1 << 1));
            iphc = (byte) (iphc | (1 << 0));
        } else {
            iphc = (byte) (iphc & ~(1 << 1));
            iphc = (byte) (iphc & ~(1 << 0));
        }

        ra[1] = iphc;
        //------------
        //add in-line parameters

        //next header (58, 0x3a) -> ICMP
        ra[2] = (byte) 0x3a;

        byte[] sa = new byte[8];
        Arrays.fill(sa, (byte) 0);

        for (int i = 0; i < 8; i++) {
            sa[i] = linkLocalRouterAddress[i + 8];
        }

        if (!fullCompression) {
            byte[] ra_sa = new byte[ra.length + sa.length];
            System.arraycopy(ra, 0, ra_sa, 0, ra.length);
            System.arraycopy(sa, 0, ra_sa, ra.length, sa.length);
            ra = ra_sa;
        }

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

        byte[] da = new byte[16];
        Arrays.fill(da, (byte) 0);

        for (int i = 0; i < 16; i++) {
            da[i] = linkLocalPeripheralAddress[i];
        }

        if (!fullCompression) {
            byte[] ra_da = new byte[ra.length + da.length];
            System.arraycopy(ra, 0, ra_da, 0, ra.length);
            System.arraycopy(da, 0, ra_da, ra.length, da.length);
            ra = ra_da;
        }

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        // ICMPv6 router advertisement

        //TODO: message validation checks (https://tools.ietf.org/html/rfc4861#page-39)

        byte[] icmp = new byte[16];
        Arrays.fill(icmp, (byte) 0);

        //icmpv6 type router advertisement (134, 0x86)
        icmp[0] = (byte) 0x86;

        //code 0
        icmp[1] = (byte) 0x00;

        //2bytes checksum
        icmp[2] = (byte) 0x00;
        icmp[3] = (byte) 0x00;

        //cur hop limit
        icmp[4] = (byte) 0x40; //0x00?

        //1bit M -> 0 (no DHCP)
        //1bit O -> 0 (no other config info is available via DHCPv6)
        //6bit reserved
        icmp[5] = (byte) 0x00;

        //router lifetime (9000 second, 0x2328)
        icmp[6] = (byte) 0x07; //0xff
        icmp[7] = (byte) 0x08; //0xff

        //reachable time
        icmp[8] = (byte) 0x00;
        icmp[9] = (byte) 0x36;
        icmp[10] = (byte) 0xee;
        icmp[11] = (byte) 0x80;

        //retrans timer
        icmp[12] = (byte) 0x00;
        icmp[13] = (byte) 0x00;
        icmp[14] = (byte) 0x00;
        icmp[15] = (byte) 0x00;

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //Possible Options: SLLAO, PIO, 6CO, ABRO

        //-------------------------------------------------------------------------
        //PIO:
        //if this option has length 4 (32) the "Unknown ND option" has value 0x22 instead of 0x20 (which is faulty)

        byte[] pio = new byte[32];
        Arrays.fill(pio, (byte) 0);

        pio[0] = (byte) 0x03; //Type: 3
        pio[1] = (byte) 0x04; //Length: 3
        pio[2] = (byte) 0x40; //Prefix Length (48) "2001:0db9:0001:0000"
        pio[3] = (byte) 0x40;//L=0, A=1, reserved
        //if not working, try L=1

        pio[4] = (byte) 0xff; //Valid Lifetime of the prefix (0xffffffff means infinite lifetime)
        pio[5] = (byte) 0xff;
        pio[6] = (byte) 0xff;
        pio[7] = (byte) 0xff;

        pio[8] = (byte) 0xff; //preferred Lifetime of the generated address from the prefix via stateless address autoconfig (0xffffffff means infinite lifetime)
        pio[9] = (byte) 0xff;
        pio[10] = (byte) 0xff;
        pio[11] = (byte) 0xff;

        pio[12] = (byte) 0x00; //Reserved, must be zero
        pio[13] = (byte) 0x00;
        pio[14] = (byte) 0x00;
        pio[15] = (byte) 0x00;

        pio[16] = (byte) 0x20; //Prefix
        pio[17] = (byte) 0x01;
        pio[18] = (byte) 0x0d;
        pio[19] = (byte) 0xb9;
        pio[20] = (byte) 0x00;
        pio[21] = (byte) 0x01;

        pio[22] = (byte) 0x00; //rest of the ipv6 address, will be ignored
        pio[23] = (byte) 0x00;

        pio[24] = (byte) 0x00;
        pio[25] = (byte) 0x00;
        pio[26] = (byte) 0x00;
        pio[27] = (byte) 0x00;
        pio[28] = (byte) 0x00;
        pio[29] = (byte) 0x00;
        pio[30] = (byte) 0x00;
        pio[31] = (byte) 0x00;

        //concat pio to icmp
        byte[] icmp_pio = new byte[icmp.length + pio.length];
        System.arraycopy(icmp, 0, icmp_pio, 0, icmp.length);
        System.arraycopy(pio, 0, icmp_pio, icmp.length, pio.length);
        icmp = icmp_pio;

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

        //new SLLAO:
        byte[] sllao = new byte[8];
        Arrays.fill(sllao, (byte) 0);
        sllao[0] = (byte) 0x01; // Type: 1 for Source Link-layer Address (2 for Target link-layer address)

        sllao[1] = (byte) 0x01; //Length of option (including type and length field) in units of 8 octets (64 bits)
        sllao[2] = linkLocalRouterAddress[8];
        sllao[3] = linkLocalRouterAddress[9];
        sllao[4] = linkLocalRouterAddress[10];
        sllao[5] = linkLocalRouterAddress[13];
        sllao[6] = linkLocalRouterAddress[14];
        sllao[7] = linkLocalRouterAddress[15];

        //concat sllao to icmp
        byte[] icmp_sllao = new byte[icmp.length + sllao.length];
        System.arraycopy(icmp, 0, icmp_sllao, 0, icmp.length);
        System.arraycopy(sllao, 0, icmp_sllao, icmp.length, sllao.length);
        icmp = icmp_sllao;

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //new 6CO:

        byte[] sixCo = new byte[16];
        Arrays.fill(sixCo, (byte) 0);

        sixCo[0] = (byte) 0x22; //Type: 34
        sixCo[1] = (byte) 0x02; //Length: (units of 8 bytes)

        sixCo[2] = (byte) 0x30; //context length: 48
        sixCo[3] = (byte) 0x10; //RES, C, CID

        sixCo[4] = (byte) 0x00; //Reserved
        sixCo[5] = (byte) 0x00;

        sixCo[6] = (byte) 0xff; //valid lifetime
        sixCo[7] = (byte) 0xff;

        sixCo[8] = (byte) 0x20;
        sixCo[9] = (byte) 0x01;
        sixCo[10] = (byte) 0x0d;
        sixCo[11] = (byte) 0xb9;
        sixCo[12] = (byte) 0x00;
        sixCo[13] = (byte) 0x01;

        sixCo[14] = (byte) 0x00; //padding to get 16 bytes full
        sixCo[15] = (byte) 0x00; //padding to get 16 bytes full

        //concat 6CO to icmp
        byte[] icmp_6co = new byte[icmp.length + sixCo.length];
        System.arraycopy(icmp, 0, icmp_6co, 0, icmp.length);
        System.arraycopy(sixCo, 0, icmp_6co, icmp.length, sixCo.length);
        icmp = icmp_6co;

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //new ABRO:
        //skip this option

        /*
        byte[] abro = new byte[24];
        Arrays.fill(abro, (byte) 0);

        abro[0] = (byte) 0x23; //Type: 35
        abro[1] = (byte) 0x03; //Length: 3

        abro[2] = (byte) 0xfa; //Version Low //TODO: check these values from the RA message
        abro[3] = (byte) 0x10;

        abro[4] = (byte) 0x30; //Version High
        abro[5] = (byte) 0x03;

        abro[6] = (byte) 0x00; //Valid Lifetime (zero means default value of ~10000 (one week))
        abro[7] = (byte) 0x00;

        abro[8] = (byte) 0x20;
        abro[9] = (byte) 0x01;
        abro[10] = (byte) 0x0d;
        abro[11] = (byte) 0xb9;
        abro[12] = (byte) 0x00;
        abro[13] = (byte) 0x01;
        abro[14] = (byte) 0x00;
        abro[15] = (byte) 0x00;

        abro[16] = (byte) 0x7a;
        abro[17] = (byte) 0xf8;
        abro[18] = (byte) 0x82;
        abro[19] = (byte) 0xff;
        abro[20] = (byte) 0xfe;
        abro[21] = (byte) 0xa4;
        abro[22] = (byte) 0xc3;
        abro[23] = (byte) 0xd3;

        //concat abro to icmp
        byte[] icmp_abro = new byte[icmp.length + abro.length];
        System.arraycopy(icmp, 0, icmp_abro, 0, icmp.length);
        System.arraycopy(abro, 0, icmp_abro, icmp.length, abro.length);
        icmp = icmp_abro;
        */

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //add IPv6-pseudoheader for computing the checksum!!!
        byte[] ipv6Pseudoheader = new byte[40];
        Arrays.fill(ipv6Pseudoheader, (byte) 0);

        //Source Address 16 byte
        for (int i = 0; i < 16; i++) {
            ipv6Pseudoheader[i] = linkLocalRouterAddress[i];
        }

        //Destination Address 16 byte
        for (int i = 0; i < 16; i++) {
            ipv6Pseudoheader[i + 16] = da[i];
        }

        //Upper-Layer Packet Length 4 byte (icmp length: )
        int lengthIcmp = icmp.length;
        ipv6Pseudoheader[35] = (byte) lengthIcmp;

        //zero 3 byte

        //Next Header 1 byte (icmp is dez 58 = 0x3a)
        ipv6Pseudoheader[39] = (byte) 0x3a;

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //checksum of icmp header with pseudo-ipv6-header before
        byte[] icmpWithPseudoIpv6Header = new byte[ipv6Pseudoheader.length + icmp.length];
        System.arraycopy(ipv6Pseudoheader, 0, icmpWithPseudoIpv6Header, 0, ipv6Pseudoheader.length);
        System.arraycopy(icmp, 0, icmpWithPseudoIpv6Header, ipv6Pseudoheader.length, icmp.length);

        long chcksm = calculateIPChecksum(icmpWithPseudoIpv6Header);
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putLong(chcksm);
        icmp[2] = buf.array()[6];
        icmp[3] = buf.array()[7];

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //concatenate icmp message with ip header
        byte[] ra_icmp = new byte[ra.length + icmp.length];
        System.arraycopy(ra, 0, ra_icmp, 0, ra.length);
        System.arraycopy(icmp, 0, ra_icmp, ra.length, icmp.length);
        ra = ra_icmp;

        Log.d("ICMP", "length of RA: " + ra.length);
        Log.d("ICMP", "content of RA: " + bytesToHex(ra));

        return ra;
    }

    public String byteToHex(byte b) {
        char[] hexChars = new char[2];
        int v = b & 0xFF;
        hexChars[0] = hexArray[v >>> 4];
        hexChars[1] = hexArray[v & 0x0F];
        return new String(hexChars);
    }

    private void analyzeEchoRequest(int bytePos, byte[] inputMessage, byte[] identifier, byte[] sequenceNumber, byte[] data) {
        Log.d(TAG_EREQU, "echoRequest: " + bytesToHex(inputMessage));

        bytePos += 4;
        identifier[0] = inputMessage[bytePos];
        identifier[1] = inputMessage[++bytePos];
        sequenceNumber[0] = inputMessage[++bytePos];
        sequenceNumber[1] = inputMessage[++bytePos];

        if (inputMessage.length > bytePos) {
            int lengthData = inputMessage.length-bytePos;
            Log.d(TAG_EREQU, "data length of echo-request: " + lengthData);

            byte[] dataBuffer = new byte[lengthData];
            int j = 0;
            for (int i = bytePos; i < inputMessage.length; i++) {
                dataBuffer[j] = inputMessage[i];
                j++;
            }
            data = dataBuffer;
        }
    }

    private byte[] getIcmpv6EchoReply(IPHC compressedHeader, byte[] identifier, byte[] sequenceNumber, byte[] data) {
        Log.d("###", "echoReply identifier: " + bytesToHex(identifier));
        Log.d("###", "echoReply sequenceNumber: " + bytesToHex(sequenceNumber));
        Log.d("###", "echoReply data: " + bytesToHex(data));

        byte[] echoReply = new byte[3];
        Arrays.fill(echoReply, (byte) 0);

        //IPHC
        byte iphc = (byte) 0;
        iphc = (byte) (iphc & ~(1 << 7));
        iphc = (byte) (iphc | (1 << 6));
        iphc = (byte) (iphc | (1 << 5));

        //TF (Traffic Class and Flow Label)
        iphc = (byte) (iphc | (1 << 4));
        iphc = (byte) (iphc | (1 << 3));

        //NH (Next Header, value 0 -> carried in-line (1 byte))
        iphc = (byte) (iphc & ~(1 << 2));

        //HLIM (Hop Limit Field, value 11 -> 255)
        iphc = (byte) (iphc | (1 << 1));
        iphc = (byte) (iphc | (1 << 0));

        echoReply[0] = iphc;
        iphc = (byte) 0;

        //CID
        iphc = (byte) (iphc & ~(1 << 7));

        //SAC
        iphc = (byte) (iphc & ~(1 << 6));

        //SAM (SAC =0, SAM=01: 64 bits with link-local prefix(fe80:0000:0000:0000))
//        if (fullCompression) {
            iphc = (byte) (iphc | (1 << 5));
            iphc = (byte) (iphc | (1 << 4));
//        } else {
//            iphc = (byte) (iphc & ~(1 << 5));
//            iphc = (byte) (iphc | (1 << 4));
//        }

        //M
        iphc = (byte) (iphc & ~(1 << 3));

        //DAC
        iphc = (byte) (iphc & ~(1 << 2));

        //DAM (M=0, DAC=0, if DAM=00: 128 bits the full ipv6 address)
//        if (fullCompression) {
            iphc = (byte) (iphc | (1 << 1));
            iphc = (byte) (iphc | (1 << 0));
//        } else {
//            iphc = (byte) (iphc & ~(1 << 1));
//            iphc = (byte) (iphc & ~(1 << 0));
//        }

        echoReply[1] = iphc;
        //------------
        // in-line parameters

        //next header (58, 0x3a) -> ICMP
        echoReply[2] = (byte) 0x3a;

        //ICMPv6 type echo reply:
        byte[] icmpMessage = new byte[8];
        Arrays.fill(icmpMessage, (byte) 0);

        icmpMessage[0] = (byte) 0x81; //dez 129 type echo reply (0x81)
        icmpMessage[1] = (byte) 0x00; //code

        //2 bytes checksum

        //identifier from echo request
        icmpMessage[4] = identifier[0];
        icmpMessage[5] = identifier[1];

        //sequence number from echo request
        icmpMessage[6] = sequenceNumber[0];
        icmpMessage[7] = sequenceNumber[1];

        if (data != null) { //add data if it was part of echo request
            byte[] echoData = new byte[data.length];
            Arrays.fill(echoData, (byte) 0);
            System.arraycopy(data, 0, echoData, 0, data.length);

            byte[] buffer = new byte[icmpMessage.length + echoData.length];
            System.arraycopy(icmpMessage, 0, buffer, 0, icmpMessage.length);
            System.arraycopy(echoData, 0, buffer, icmpMessage.length, echoData.length);
            icmpMessage = buffer;
        }

        Log.d("###", "echo reply message: " + bytesToHex(icmpMessage));

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //create ipv6-pseudo-header for calculating checksum
        byte[] ipv6Pseudoheader = new byte[40];
        Arrays.fill(ipv6Pseudoheader, (byte) 0);
        //Source Address 16 byte
        for (int i = 0; i < 16; i++) {
            ipv6Pseudoheader[i] = linkLocalRouterAddress[i];
        }
        ipv6Pseudoheader[8] = manualDeviceAddressBuffer[0];
        ipv6Pseudoheader[9] = manualDeviceAddressBuffer[1];
        ipv6Pseudoheader[10] = manualDeviceAddressBuffer[2];
        ipv6Pseudoheader[13] = manualDeviceAddressBuffer[3];
        ipv6Pseudoheader[14] = manualDeviceAddressBuffer[4];
        ipv6Pseudoheader[15] = manualDeviceAddressBuffer[5];

        //Destination Address 16 byte
        for (int i = 0; i < 16; i++) {
            ipv6Pseudoheader[i + 16] = linkLocalPeripheralAddress[i];
        }
        //test to force an "icmpv6 drop packet beacuse of false checksum" error message
        //ipv6Pseudoheader[20] = (byte) 0x00;
        //ipv6Pseudoheader[21] = (byte) 0x00;

        //Upper-Layer Packet Length 4 byte (icmp length: )
        int lengthIcmp = icmpMessage.length;
        ipv6Pseudoheader[35] = (byte) lengthIcmp;

        //zero 3 byte

        //Next Header 1 byte (icmp is dez 58 = 0x3a)
        ipv6Pseudoheader[39] = (byte) 0x3a;

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //checksum of icmp header with pseudo-ipv6-header before
        byte[] icmpWithPseudoIpv6Header = new byte[ipv6Pseudoheader.length + icmpMessage.length];
        System.arraycopy(ipv6Pseudoheader, 0, icmpWithPseudoIpv6Header, 0, ipv6Pseudoheader.length);
        System.arraycopy(icmpMessage, 0, icmpWithPseudoIpv6Header, ipv6Pseudoheader.length, icmpMessage.length);

        long chcksm = calculateIPChecksum(icmpWithPseudoIpv6Header);
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putLong(chcksm);
        icmpMessage[2] = buf.array()[6];
        icmpMessage[3] = buf.array()[7];

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //concatenate icmp message with ip header
        byte[] echoReply_icmp = new byte[echoReply.length + icmpMessage.length];
        System.arraycopy(echoReply, 0, echoReply_icmp, 0, echoReply.length);
        System.arraycopy(icmpMessage, 0, echoReply_icmp, echoReply.length, icmpMessage.length);
        echoReply = echoReply_icmp;

        return echoReply;
    }

    private byte[] getIcmpv6EchoRequest() {
        byte[] echoRequest = new byte[3];
        Arrays.fill(echoRequest, (byte) 0);

        //IPHC
        byte iphc = (byte) 0;

        iphc = (byte) (iphc & ~(1 << 7));
        iphc = (byte) (iphc | (1 << 6));
        iphc = (byte) (iphc | (1 << 5));

        //TF (Traffic Class and Flow Label)
        iphc = (byte) (iphc | (1 << 4));
        iphc = (byte) (iphc | (1 << 3));

        //NH (Next Header, value 0 -> carried in-line (1 byte))
        iphc = (byte) (iphc & ~(1 << 2));

        //HLIM (Hop Limit Field, value 11 -> 255)
        iphc = (byte) (iphc | (1 << 1));
        iphc = (byte) (iphc | (1 << 0));

        echoRequest[0] = iphc;
        iphc = (byte) 0;

        //CID
        iphc = (byte) (iphc & ~(1 << 7));

        //SAC
        iphc = (byte) (iphc & ~(1 << 6));

        //SAM (SAC =0, SAM=01: 64 bits with link-local prefix(fe80:0000:0000:0000))
//      if (fullCompression) {
        iphc = (byte) (iphc | (1 << 5));
        iphc = (byte) (iphc | (1 << 4));
//      } else {
//          iphc = (byte) (iphc & ~(1 << 5));
//          iphc = (byte) (iphc | (1 << 4));
//      }

        //M
        iphc = (byte) (iphc & ~(1 << 3));

        //DAC
        iphc = (byte) (iphc & ~(1 << 2));

        //DAM (M=0, DAC=0, if DAM=00: 128 bits the full ipv6 address)
//      if (fullCompression) {
        iphc = (byte) (iphc | (1 << 1));
        iphc = (byte) (iphc | (1 << 0));
//      } else {
//          iphc = (byte) (iphc & ~(1 << 1));
//          iphc = (byte) (iphc & ~(1 << 0));
//      }

        echoRequest[1] = iphc;
        //------------
        // in-line parameters

        //next header (58, 0x3a) -> ICMP
        echoRequest[2] = (byte) 0x3a;

        //ICMPv6 type echo reply:
        byte[] icmpMessage = new byte[8];
        Arrays.fill(icmpMessage, (byte) 0);

        icmpMessage[0] = (byte) 0x80; //dez 128 type echo reply
        icmpMessage[1] = (byte) 0x00; //code

        //2 bytes checksum

        //arbitrary identifier, zB 0x7B77
        icmpMessage[4] = (byte) 0x7B;
        icmpMessage[5] = (byte) 0x77;

        //sequence number, zB 0x56F6
        icmpMessage[6] = (byte) 0x56;
        icmpMessage[7] = (byte) 0xF6;

        //add any data if wanted, but isn't necessary
        /*
        if (data != null) { //add data if it was part of echo request
            byte[] echoData = new byte[data.length];
            Arrays.fill(echoData, (byte) 0);
            System.arraycopy(data, 0, echoData, 0, data.length);

            byte[] buffer = new byte[icmpMessage.length + echoData.length];
            System.arraycopy(icmpMessage, 0, buffer, 0, icmpMessage.length);
            System.arraycopy(echoData, 0, buffer, icmpMessage.length, echoData.length);
            icmpMessage = buffer;
        }
        */

        Log.d("###", "echo reply message: " + bytesToHex(icmpMessage));

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //create ipv6-pseudo-header for calculating checksum
        byte[] ipv6Pseudoheader = new byte[40];
        Arrays.fill(ipv6Pseudoheader, (byte) 0);
        //Source Address 16 byte
        for (int i = 0; i < 16; i++) {
            ipv6Pseudoheader[i] = linkLocalRouterAddress[i];
        }
        ipv6Pseudoheader[8] = manualDeviceAddressBuffer[0];
        ipv6Pseudoheader[9] = manualDeviceAddressBuffer[1];
        ipv6Pseudoheader[10] = manualDeviceAddressBuffer[2];
        ipv6Pseudoheader[13] = manualDeviceAddressBuffer[3];
        ipv6Pseudoheader[14] = manualDeviceAddressBuffer[4];
        ipv6Pseudoheader[15] = manualDeviceAddressBuffer[5];

        //Destination Address 16 byte
        for (int i = 0; i < 16; i++) {
            ipv6Pseudoheader[i + 16] = linkLocalPeripheralAddress[i];
        }
        //test to force an "icmpv6 drop packet beacuse of false checksum" error message
        //ipv6Pseudoheader[20] = (byte) 0x00;
        //ipv6Pseudoheader[21] = (byte) 0x00;

        //Upper-Layer Packet Length 4 byte (icmp length: )
        int lengthIcmp = icmpMessage.length;
        ipv6Pseudoheader[35] = (byte) lengthIcmp;

        //zero 3 byte

        //Next Header 1 byte (icmp is dez 58 = 0x3a)
        ipv6Pseudoheader[39] = (byte) 0x3a;

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //checksum of icmp header with pseudo-ipv6-header before
        byte[] icmpWithPseudoIpv6Header = new byte[ipv6Pseudoheader.length + icmpMessage.length];
        System.arraycopy(ipv6Pseudoheader, 0, icmpWithPseudoIpv6Header, 0, ipv6Pseudoheader.length);
        System.arraycopy(icmpMessage, 0, icmpWithPseudoIpv6Header, ipv6Pseudoheader.length, icmpMessage.length);

        long chcksm = calculateIPChecksum(icmpWithPseudoIpv6Header);
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putLong(chcksm);
        icmpMessage[2] = buf.array()[6];
        icmpMessage[3] = buf.array()[7];

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //concatenate icmp message with ip header
        byte[] echoReply_icmp = new byte[echoRequest.length + icmpMessage.length];
        System.arraycopy(echoRequest, 0, echoReply_icmp, 0, echoRequest.length);
        System.arraycopy(icmpMessage, 0, echoReply_icmp, echoRequest.length, icmpMessage.length);
        echoRequest = echoReply_icmp;

        return echoRequest;
    }

    private byte[] getNeighborAdvertisement(IPHC compressedHeader) {
        byte[] na = new byte[3];
        Arrays.fill(na, (byte) 0);

        //IPHC
        byte iphc = (byte) 0;
        iphc = (byte) (iphc & ~(1 << 7));
        iphc = (byte) (iphc | (1 << 6));
        iphc = (byte) (iphc | (1 << 5));

        //TF (Traffic Class and Flow Label)
        iphc = (byte) (iphc | (1 << 4));
        iphc = (byte) (iphc | (1 << 3));

        //NH (Next Header, value 0 -> carried in-line (1 byte))
        iphc = (byte) (iphc & ~(1 << 2));

        //HLIM (Hop Limit Field, value 11 -> 255)
        iphc = (byte) (iphc | (1 << 1));
        iphc = (byte) (iphc | (1 << 0));

        na[0] = iphc;
        iphc = (byte) 0;

        //CID
        iphc = (byte) (iphc & ~(1 << 7));

        //SAC
        iphc = (byte) (iphc & ~(1 << 6));

        //SAM (SAC =0, SAM=01: 64 bits with link-local prefix(fe80:0000:0000:0000))
        if (fullCompression) {
            iphc = (byte) (iphc | (1 << 5));
            iphc = (byte) (iphc | (1 << 4));
        } else {
            iphc = (byte) (iphc & ~(1 << 5));
            iphc = (byte) (iphc | (1 << 4));
        }

        //M
        iphc = (byte) (iphc & ~(1 << 3));

        //DAC
        iphc = (byte) (iphc & ~(1 << 2));

        //DAM (M=0, DAC=0, if DAM=00: 128 bits the full ipv6 address)
        if (fullCompression) {
            iphc = (byte) (iphc | (1 << 1));
            iphc = (byte) (iphc | (1 << 0));
        } else {
            iphc = (byte) (iphc & ~(1 << 1));
            iphc = (byte) (iphc & ~(1 << 0));
        }

        na[1] = iphc;
        //------------
        // in-line parameters

        //next header (58, 0x3a) -> ICMP
        na[2] = (byte) 0x3a;

        byte[] sa = new byte[8];
        Arrays.fill(sa, (byte) 0);

        for (int i = 0; i < 8; i++) {
            sa[i] = linkLocalRouterAddress[i + 8];
        }

        if (!fullCompression) {
            byte[] ra_sa = new byte[na.length + sa.length];
            System.arraycopy(na, 0, ra_sa, 0, na.length);
            System.arraycopy(sa, 0, ra_sa, na.length, sa.length);
            na = ra_sa;
        }

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        // destination address
        byte[] da = new byte[16];
        Arrays.fill(da, (byte) 0);

        if (compressedHeader.SAC && compressedHeader.SAM == 0) {
            System.arraycopy(linkLocalPeripheralAddress, 0, da, 0, linkLocalPeripheralAddress.length);
        } else {
            System.arraycopy(linkLocalPeripheralAddress, 0, da, 0, linkLocalPeripheralAddress.length);
        }

        if (!fullCompression) {
            byte[] ra_da = new byte[na.length + da.length];
            System.arraycopy(na, 0, ra_da, 0, na.length);
            System.arraycopy(da, 0, ra_da, na.length, da.length);
            na = ra_da;
        }

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        // ICMPv6 neighbor advertisement

        byte[] icmp = new byte[24];
        Arrays.fill(icmp, (byte) 0);

        //icmpv6 type neighbor advertisement (136, 0x88)
        icmp[0] = (byte) 0x88;

        //code 0
        icmp[1] = (byte) 0x00;

        //2bytes checksum
        icmp[2] = (byte) 0x00;
        icmp[3] = (byte) 0x00;

        // 1 bit R -> 1 (Sender is a Router)
        // 1 bit S -> 1 (advertisement is sent in response to a NS-message)
        // 1 bit O -> 1 (override an existing cache entry and update the cached link-layer address)
        icmp[4] = (byte) 0xc0; //0xe0

        //Reserved
        icmp[5] = (byte) 0x00;
        icmp[6] = (byte) 0x00;
        icmp[7] = (byte) 0x00;

        for (int i = 0; i < compressedHeader.targetAddress.length; i++) {
            icmp[i+8] = compressedHeader.targetAddress[i];
        }

        Log.d("###TARGET_ADDRESS", "target address of neighbor solicitation: " + bytesToHex(compressedHeader.targetAddress));
        Log.d("###TARGET_ADDRESS", "icmp message NA (including target address): " + bytesToHex(icmp));

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //Options
        // SLLAO with target link-layer address -> TLLAO
        // ARO with Status

        //TLLAO
        //Link Layer Address of the sender of the advertisement (router)
        byte[] tllao = new byte[8];
        Arrays.fill(tllao, (byte) 0);
        tllao[0] = (byte) 0x02; // Type: 1 for Source Link-layer Address (2 for Target link-layer address)

        tllao[1] = (byte) 0x01; //Length of option (including type and length field) in units of 8 octets (64 bits)
        tllao[2] = linkLocalRouterAddress[8];
        tllao[3] = linkLocalRouterAddress[9];
        tllao[4] = linkLocalRouterAddress[10];
        tllao[5] = linkLocalRouterAddress[13];
        tllao[6] = linkLocalRouterAddress[14];
        tllao[7] = linkLocalRouterAddress[15];

        //concat tllao to icmp
        byte[] icmp_tllao = new byte[icmp.length + tllao.length];
        System.arraycopy(icmp, 0, icmp_tllao, 0, icmp.length);
        System.arraycopy(tllao, 0, icmp_tllao, icmp.length, tllao.length);
        icmp = icmp_tllao;

        //++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        // ARO with Status

        byte[] aro = new byte[16];
        Arrays.fill(aro, (byte) 0);

        aro[0] = (byte) 0x21; //Type: 35
        aro[1] = (byte) 0x02; //Length: 2
        aro[2] = (byte) 0x03; //Status: 3-255
        aro[3] = (byte) 0x00; //Reserved
        aro[4] = (byte) 0x00;
        aro[5] = (byte) 0x00;
        aro[6] = (byte) 0xff; //Registration Lifetime
        aro[7] = (byte) 0xff;

        //source address of the border router fe80::7af8:82ff:fea4:c3d3
        for (int i = 8; i < 16; i++) {
            aro[i] = linkLocalRouterAddress[i];
        }

        //concat aro to icmp
        byte[] icmp_aro = new byte[icmp.length + aro.length];
        System.arraycopy(icmp, 0, icmp_aro, 0, icmp.length);
        System.arraycopy(aro, 0, icmp_aro, icmp.length, aro.length);
        icmp = icmp_aro;

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //add IPv6-pseudoheader for computing the checksum!!!
        byte[] ipv6Pseudoheader = new byte[40];
        Arrays.fill(ipv6Pseudoheader, (byte) 0);

        //Source Address 16 byte
        for (int i = 0; i < 16; i++) {
            ipv6Pseudoheader[i] = linkLocalRouterAddress[i];
        }

        //Destination Address 16 byte
        for (int i = 0; i < 16; i++) {
            ipv6Pseudoheader[i + 16] = da[i];
        }

        //test to force an "icmpv6 drop packet beacuse of false checksum" error message
        //ipv6Pseudoheader[20] = (byte) 0x00;
        //ipv6Pseudoheader[21] = (byte) 0x00;

        //Upper-Layer Packet Length 4 byte (icmp length: )
        int lengthIcmp = icmp.length;
        ipv6Pseudoheader[35] = (byte) lengthIcmp;

        //zero 3 byte

        //Next Header 1 byte (icmp is dez 58 = 0x3a)
        ipv6Pseudoheader[39] = (byte) 0x3a;

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //checksum of icmp header with pseudo-ipv6-header before
        byte[] icmpWithPseudoIpv6Header = new byte[ipv6Pseudoheader.length + icmp.length];
        System.arraycopy(ipv6Pseudoheader, 0, icmpWithPseudoIpv6Header, 0, ipv6Pseudoheader.length);
        System.arraycopy(icmp, 0, icmpWithPseudoIpv6Header, ipv6Pseudoheader.length, icmp.length);

        long chcksm = calculateIPChecksum(icmpWithPseudoIpv6Header);
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putLong(chcksm);
        icmp[2] = buf.array()[6];
        icmp[3] = buf.array()[7];

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //concatenate icmp message with ip header
        byte[] na_icmp = new byte[na.length + icmp.length];
        System.arraycopy(na, 0, na_icmp, 0, na.length);
        System.arraycopy(icmp, 0, na_icmp, na.length, icmp.length);
        na = na_icmp;

        Log.d("###NA", "length of the NA message: " + na.length);
        Log.d("###NA", "NA message: " + bytesToHex(na));

        return na;
    }

    private byte[] getNeighborSolicitation() {
        byte[] ns = new byte[3];
        Arrays.fill(ns, (byte) 0);

        //IPHC
        byte iphc = (byte) 0;

        iphc = (byte) (iphc & ~(1 << 7));
        iphc = (byte) (iphc | (1 << 6));
        iphc = (byte) (iphc | (1 << 5));

        //TF (Traffic Class and Flow Label)
        iphc = (byte) (iphc | (1 << 4));
        iphc = (byte) (iphc | (1 << 3));

        //NH (Next Header, value 0 -> carried in-line (1 byte))
        iphc = (byte) (iphc & ~(1 << 2));

        //HLIM (Hop Limit Field, value 11 -> 255)
        iphc = (byte) (iphc | (1 << 1));
        iphc = (byte) (iphc | (1 << 0));

        ns[0] = iphc;
        iphc = (byte) 0;

        //CID
        iphc = (byte) (iphc & ~(1 << 7));

        //SAC
        iphc = (byte) (iphc & ~(1 << 6));

        //SAM (SAC =0, SAM=01: 64 bits with link-local prefix(fe80:0000:0000:0000))
        if (fullCompression) {
            iphc = (byte) (iphc | (1 << 5));
            iphc = (byte) (iphc | (1 << 4));
        } else {
            iphc = (byte) (iphc & ~(1 << 5));
            iphc = (byte) (iphc | (1 << 4));
        }

        //M
        iphc = (byte) (iphc & ~(1 << 3));

        //DAC
        iphc = (byte) (iphc & ~(1 << 2));

        //DAM (M=0, DAC=0, if DAM=00: 128 bits the full ipv6 address)
//      if (fullCompression) {
//          iphc = (byte) (iphc | (1 << 1));
//          iphc = (byte) (iphc | (1 << 0));
//      } else {
            iphc = (byte) (iphc & ~(1 << 1));
            iphc = (byte) (iphc & ~(1 << 0));
//      }

        ns[1] = iphc;
        //------------
        // in-line parameters

        //next header (58, 0x3a) -> ICMP
        ns[2] = (byte) 0x3a;

        byte[] sa = new byte[8];
        Arrays.fill(sa, (byte) 0);

        for (int i = 0; i < 8; i++) {
            sa[i] = linkLocalRouterAddress[i + 8];
        }

        if (!fullCompression) {
            byte[] ra_sa = new byte[ns.length + sa.length];
            System.arraycopy(ns, 0, ra_sa, 0, ns.length);
            System.arraycopy(sa, 0, ra_sa, ns.length, sa.length);
            ns = ra_sa;
        }

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        byte[] da = new byte[16];
        Arrays.fill(da, (byte) 0);

        System.arraycopy(allNodesMulticastAddress, 0, da, 0, allNodesMulticastAddress.length);

//      if (!fullCompression) {
          byte[] ra_da = new byte[ns.length + da.length];
          System.arraycopy(ns, 0, ra_da, 0, ns.length);
          System.arraycopy(da, 0, ra_da, ns.length, da.length);
          ns = ra_da;
//      }

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //------------
        // ICMPv6 neighbor advertisement

        byte[] icmp = new byte[24];
        Arrays.fill(icmp, (byte) 0);

        //icmpv6 type neighbor solicitation (135, 0x87)
        icmp[0] = (byte) 0x87;

        //code 0
        icmp[1] = (byte) 0x00;

        //2bytes checksum
        icmp[2] = (byte) 0x00;
        icmp[3] = (byte) 0x00;

        // 1 bit R -> 1 (Sender is a Router)
        // 1 bit S -> 1 (advertisement is sent in response to a NS-message)
        // 1 bit O -> 1 (override an existing cache entry and update the cached link-layer address)
        icmp[4] = (byte) 0xe0;

        //Reserved
        icmp[5] = (byte) 0x00;
        icmp[6] = (byte) 0x00;
        icmp[7] = (byte) 0x00;

        icmp[16] = ownDeviceAddress[0];
        icmp[17] = ownDeviceAddress[1];
        icmp[18] = ownDeviceAddress[2];
        icmp[19] = (byte) 0xff;
        icmp[20] = (byte) 0xfe;
        icmp[21] = ownDeviceAddress[3];
        icmp[22] = ownDeviceAddress[4];
        icmp[23] = ownDeviceAddress[5];

        Log.d("###TARGET_ADDRESS", "icmp message: " + bytesToHex(icmp));

        return ns;
    }

    private byte[] getEchoRequest() {
        byte[] echoRequest = new byte[3];
        Arrays.fill(echoRequest, (byte) 0);

        //IPHC
        byte iphc = (byte) 0;
        iphc = (byte) (iphc & ~(1 << 7));
        iphc = (byte) (iphc | (1 << 6));
        iphc = (byte) (iphc | (1 << 5));

        //TF (Traffic Class and Flow Label)
        iphc = (byte) (iphc | (1 << 4));
        iphc = (byte) (iphc | (1 << 3));

        //NH (Next Header, value 0 -> carried in-line (1 byte))
        iphc = (byte) (iphc & ~(1 << 2));

        //HLIM (Hop Limit Field, value 11 -> 255)
        iphc = (byte) (iphc | (1 << 1));
        iphc = (byte) (iphc | (1 << 0));

        echoRequest[0] = iphc;
        iphc = (byte) 0;

        //CID
        iphc = (byte) (iphc & ~(1 << 7));

        //SAC
        iphc = (byte) (iphc & ~(1 << 6));

        //SAM (SAC =0, SAM=01: 64 bits with link-local prefix(fe80:0000:0000:0000))
        iphc = (byte) (iphc & ~(1 << 5));
        iphc = (byte) (iphc | (1 << 4));

        //M
        iphc = (byte) (iphc & ~(1 << 3));

        //DAC
        iphc = (byte) (iphc & ~(1 << 2));

        //DAM (M=0, DAC=0, if DAM=00: 128 bits the full ipv6 address)
        iphc = (byte) (iphc & ~(1 << 1));
        iphc = (byte) (iphc & ~(1 << 0));

        echoRequest[1] = iphc;
        //------------
        // in-line parameters

        //next header (58, 0x3a) -> ICMP
        echoRequest[2] = (byte) 0x3a;

        //-----------------------------------
        // source address

        byte[] sa = new byte[8];
        Arrays.fill(sa, (byte) 0);

        for (int i = 0; i < 8; i++) {
            sa[i] = linkLocalRouterAddress[i + 8];
        }

        byte[] er_sa = new byte[echoRequest.length + sa.length];
        System.arraycopy(echoRequest, 0, er_sa, 0, echoRequest.length);
        System.arraycopy(sa, 0, er_sa, echoRequest.length, sa.length);
        echoRequest = er_sa;

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //destination address

        byte[] da = new byte[16];
        Arrays.fill(da, (byte) 0);

        for (int i = 0; i < 16; i++) {
            da[i] = linkLocalPeripheralAddress[i];
        }

        byte[] er_da = new byte[echoRequest.length + da.length];
        System.arraycopy(echoRequest, 0, er_da, 0, echoRequest.length);
        System.arraycopy(da, 0, er_da, echoRequest.length, da.length);
        echoRequest = er_da;

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        // ICMPv6 echo request

        byte[] icmp = new byte[24];
        Arrays.fill(icmp, (byte) 0);

        //icmpv6 type echo request (128, 0x80)
        icmp[0] = (byte) 0x80;

        //code 0
        icmp[1] = (byte) 0x00;

        //2bytes checksum
        icmp[2] = (byte) 0x00;
        icmp[3] = (byte) 0x00;

        //Identifier
        icmp[4] = (byte) 0x8f;
        icmp[5] = (byte) 0xf1;

        //Sequence Number
        icmp[6] = (byte) 0x00;
        icmp[7] = (byte) 0xf3;

        //Optional Data

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //add IPv6-pseudoheader for computing the checksum!!!
        byte[] ipv6Pseudoheader = new byte[40];
        Arrays.fill(ipv6Pseudoheader, (byte) 0);

        //Source Address 16 byte
        for (int i = 0; i < 16; i++) {
            ipv6Pseudoheader[i] = linkLocalRouterAddress[i];
        }

        //Destination Address 16 byte
        for (int i = 0; i < 16; i++) {
            ipv6Pseudoheader[i + 16] = da[i];
        }

        //Upper-Layer Packet Length 4 byte (icmp length: )
        int lengthIcmp = icmp.length;
        ipv6Pseudoheader[35] = (byte) lengthIcmp;

        //zero 3 byte

        //Next Header 1 byte (icmp is dez 58 = 0x3a)
        ipv6Pseudoheader[39] = (byte) 0x3a;

        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        //checksum of icmp header with pseudo-ipv6-header before
        byte[] icmpWithPseudoIpv6Header = new byte[ipv6Pseudoheader.length + icmp.length];
        System.arraycopy(ipv6Pseudoheader, 0, icmpWithPseudoIpv6Header, 0, ipv6Pseudoheader.length);
        System.arraycopy(icmp, 0, icmpWithPseudoIpv6Header, ipv6Pseudoheader.length, icmp.length);

        long chcksm = calculateIPChecksum(icmpWithPseudoIpv6Header);
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putLong(chcksm);
        icmp[2] = buf.array()[6];
        icmp[3] = buf.array()[7];

        //Add icmp to er
        byte[] er_icmp = new byte[echoRequest.length + icmp.length];
        System.arraycopy(echoRequest, 0, er_icmp, 0, echoRequest.length);
        System.arraycopy(icmp, 0, er_icmp, echoRequest.length, icmp.length);
        echoRequest = er_icmp;

        return echoRequest;
    }

    private byte[] getIPv6Packet() {
        byte[] packet = new byte[3];

        Arrays.fill(packet, (byte) 0);

        //IPHC
        byte iphc = (byte) 0;

        //011
        iphc = (byte) (iphc & ~(1 << 7));
        iphc = (byte) (iphc | (1 << 6));
        iphc = (byte) (iphc | (1 << 5));


        //TF (Traffic Class and Flow Label) 11
        iphc = (byte) (iphc | (1 << 4));
        iphc = (byte) (iphc | (1 << 3));

        //NH (Next Header) 0
        iphc = (byte) (iphc & ~(1 << 2));

        //HLIM (Hop Limit Field, value 11 -> 255) 11
        iphc = (byte) (iphc | (1 << 1));
        iphc = (byte) (iphc | (1 << 0));


        packet[0] = iphc;
        iphc = (byte) 0;

        //CID is 0
        iphc = (byte) (iphc & ~(1 << 7));

        //SAC is 0
        iphc = (byte) (iphc & ~(1 << 6));


        //SAM (SAC =0, SAM=01: 64 bits with link-local prefix(fe80:0000:0000:0000))
        iphc = (byte) (iphc & ~(1 << 5));
        iphc = (byte) (iphc | (1 << 4));

        //M
        iphc = (byte) (iphc & ~(1 << 3));

        //DAC
        iphc = (byte) (iphc & ~(1 << 2));

        //DAM (M=0, DAC=0, if DAM=00: 128 bits the full ipv6 address)
        iphc = (byte) (iphc & ~(1 << 1));
        iphc = (byte) (iphc & ~(1 << 0));


        packet[1] = iphc;
        //------------

        packet[2] = (byte) 0x11; //udp

        byte[] sa = new byte[8];
        Arrays.fill(sa, (byte) 0);

        for (int i = 0; i < 8; i++) {
            sa[i] = linkLocalRouterAddress[i+8];
        }

        byte[] packet_sa = new byte[packet.length + sa.length];
        System.arraycopy(packet, 0, packet_sa, 0, packet.length);
        System.arraycopy(sa, 0, packet_sa, packet.length, sa.length);
        packet = packet_sa;


        //+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

        byte[] da = new byte[16];
        Arrays.fill(da, (byte) 0);

        for (int i = 0; i < 16; i++) {
            da[i] = linkLocalPeripheralAddress[i];
        }

        byte[] packet_da = new byte[packet.length + da.length];
        System.arraycopy(packet, 0, packet_da, 0, packet.length);
        System.arraycopy(da, 0, packet_da, packet.length, da.length);
        packet = packet_da;


        //--------------------------------------------------------------------------
        //

        byte[] udp = new byte[16];
        Arrays.fill(da, (byte) 0xfe);


        byte[] packet_data = new byte[packet.length + udp.length];
        System.arraycopy(packet, 0, packet_data, 0, packet.length);
        System.arraycopy(udp, 0, packet_data, packet.length, udp.length);
        packet = packet_data;

        return packet;
    }

}
