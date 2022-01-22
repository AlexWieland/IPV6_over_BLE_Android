package com.example.ipspapplication;

import android.util.Log;

import java.util.Arrays;

public class IPHC {
    private final String TAG_IPHC = "IPHC"; //IP Header Comppression



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


    public void decompressIPHC(byte[] inputMessage) {
        byte firstByte = inputMessage[0];
        byte secondByte = inputMessage[1];

        Log.d(TAG_IPHC, "IPHC decompressed: ");

        //011
        if (getBit(firstByte, 7) > 0 || getBit(firstByte, 6) == 0 || getBit(firstByte, 5) == 0) {
            Log.e(TAG_IPHC, "ERROR IPHC: 011 bits of IPHC doesnt exist");
        }

        //TF
        if (getBit(firstByte, 4) == 0) { //TF: 0?
            if (getBit(firstByte, 3) == 0) { //TF: 00
                Log.d(TAG_IPHC, "TF 00: ECN + DSCP + 4-bit Pad + Flow Label (4 bytes)");
                TF = 0;
            } else { //TF:01
                Log.d(TAG_IPHC, "TF 01: ECN + 2-bit Pad + Flow Label (3 bytes), DSCP is elided");
                TF = 1;
            }

        } else if (getBit(firstByte, 4) > 0) { //TF: 1?
            if (getBit(firstByte, 3) == 0) { //TF: 10
                Log.d(TAG_IPHC, "TF 10: ECN + DSCP (1 byte), Flow Label is elided");
                TF = 2;
            } else { //TF:11
                Log.d(TAG_IPHC, "TF 11: Traffic Class and Flow Label are elided");
                TF = 3;
            }

        } else {
            Log.e(TAG_IPHC, "ERROR IPHC: TF bits");
            TF = 5;
        }

        //NH
        if (getBit(firstByte, 2) == 0) { //NH: 0
            Log.d(TAG_IPHC, "NH 0: Full 8 bits for Next Header are carried in-line");
            NH = false;
        } else { //NH: 1
            Log.d(TAG_IPHC, "NH 1: Next Header field is compressed and encoded using LOWPAN_NHC");
            NH = true;
        }

        //HLIM
        if (getBit(firstByte, 1) == 0) { //HLIM: 0?
            if (getBit(firstByte, 0) == 0) { //HLIM: 00
                Log.d(TAG_IPHC, "HLIM 00: Hop Limit field is carried in-line");
                HLIM = 0;
            } else { //HLIM: 01
                Log.d(TAG_IPHC, "HLIM 01: Hop Limit Field is compressed and hop limit is 1");
                HLIM = 1;
            }

        } else if (getBit(firstByte, 1) > 0) { //HLIM: 1?
            if (getBit(firstByte, 0) == 0) { //HLIM: 10
                Log.d(TAG_IPHC, "HLIM 10: Hop Limit Field is compressed and hop limit is 64");
                HLIM = 2;
            } else { //HLIM: 11
                Log.d(TAG_IPHC, "HLIM 11: Hop Limit Field is compressed and hop limit is 255");
                HLIM = 3;
            }

        } else {
            Log.e(TAG_IPHC, "ERROR IPHC: HLIM bits");
            HLIM = 5;
        }

        //CID
        if (getBit(secondByte, 7) == 0) { //CID: 0
            Log.d(TAG_IPHC, "CID 0: No additional 8-bit Context Identifier Extension is used");
            CID = false;
        } else { //CID: 1
            Log.d(TAG_IPHC, "CID 1: An additional 8-bit Context Identifier Extension field" +
                    " immediately follows the Destination Address Mode (DAM) field");
            CID = true;
        }

        //SAC
        if (getBit(secondByte, 6) == 0) { //SAC: 0
            Log.d(TAG_IPHC, "SAC 0: Source address compression uses stateless compression.");
            SAC = false;
        } else { //SAC: 1
            Log.d(TAG_IPHC, "SAC 1: Source address compression uses stateful, context-based compression.");
            SAC = true;
        }

        //SAM
        if (SAC == false) { //if SAC is 0
            if (getBit(secondByte, 5) == 0) { //0?
                if (getBit(secondByte, 4) == 0) { //00
                    Log.d(TAG_IPHC, "SAM 00: full address is carried in-line");
                    SAM = 0;
                } else { //01
                    Log.d(TAG_IPHC, "SAM 01: 64 bits.  The first 64-bits of the address are elided." +
                            "The value of those bits is the link-local prefix padded with zeros. The remaining 64 bits are carried in-line.");
                    SAM = 1;
                }

            } else if (getBit(secondByte, 5) > 0) { //1?
                if (getBit(secondByte, 4) == 0) { //10
                    Log.d(TAG_IPHC, "SAM 10: 16 bits.  The first 112 bits of the address are elided. The value of the first 64 bits is the link-local prefix " +
                            "padded with zeros.  The following 64 bits are 0000:00ff: fe00:XXXX, where XXXX are the 16 bits carried in-line.");
                    SAM = 2;
                } else { //11
                    Log.d(TAG_IPHC, "SAM 11: 0 bits.  The address is fully elided. first 64bits of the address are the link-local prefix " +
                            "padded with zeros. re remaining 64bits are computed form the encapsulating header.");
                    SAM = 3;
                }

            } else {
                Log.e(TAG_IPHC, "ERROR IPHC: SAC bits");
                SAM = 5;
            }

        } else { //if SAC is 1
            if (getBit(secondByte, 5) == 0) { //0?
                if (getBit(secondByte, 4) == 0) { //00
                    Log.d(TAG_IPHC, "SAM 00: The UNSPECIFIED address, ::");
                    SAM = 0;
                } else { //01
                    Log.d(TAG_IPHC, "SAM 01: 64 bits.  The address is derived using context information and the 64 bits carried in-line.");
                    SAM = 1;
                }

            } else if (getBit(secondByte, 5) > 0) { //1?
                if (getBit(secondByte, 4) == 0) { //10
                    Log.d(TAG_IPHC, "SAM 10: 16 bits.  The address is derived using context information\n" +
                            " and the 16 bits carried in-line. Any IID bits not covered by\n" +
                            " context information are taken directly from their\n" +
                            " corresponding bits in the 16-bit to IID mapping given by\n" +
                            " 0000:00ff:fe00:XXXX, where XXXX are the 16 bits carried in-\n" +
                            " line.  Any remaining bits are zero.");
                    SAM = 2;
                } else { //11
                    Log.d(TAG_IPHC, "SAM 11: 0 bits.  The address is fully elided and is derived using context information and the encapsulating header");
                    SAM = 3;
                }

            } else {
                Log.e(TAG_IPHC, "ERROR IPHC: SAM bits");
                SAM = 5;
            }
        }

        //M
        if (getBit(secondByte, 3) == 0) { // 0
            Log.d(TAG_IPHC, "M 0: Destination address is not a multicast address");
            M = false;
        } else { // 1
            Log.d(TAG_IPHC, "M 1: Destination address is a multicast address");
            M = true;
        }

        //DAC
        if (getBit(secondByte, 2) == 0) { // 0
            Log.d(TAG_IPHC, "DAC 0: Destination address compression uses stateless compression");
            DAC = false;
        } else { // 1
            Log.d(TAG_IPHC, "DAC 1: Destination address compression uses stateful, context-based compression");
            DAC = true;
        }

        //DAM
        if (M == false) { //M is 0
            if (DAC == false) { //if DAC is 0
                if (getBit(secondByte, 1) == 0) { //0?
                    if (getBit(secondByte, 0) == 0) { //00
                        Log.d(TAG_IPHC, "DAM 00: 128 bits.  The full address is carried in-line");
                        DAM = 0;
                    } else { //01
                        Log.d(TAG_IPHC, "DAM 01: 64 bits.  The first 64-bits of the address are elided." +
                                "The value of those bits is the link-local prefix padded with zeros. The remaining 64 bits are carried in-line.");
                        DAM = 1;
                    }

                } else if (getBit(secondByte, 1) > 0) { //1?
                    if (getBit(secondByte, 0) == 0) { //10
                        Log.d(TAG_IPHC, "DAM 10: 16 bits.  The first 112 bits of the address are elided. The value of the first 64 bits is the link-local prefix\n" +
                                " padded with zeros.  The following 64 bits are 0000:00ff:fe00:XXXX, where XXXX are the 16 bits carried in-line.");
                        DAM = 2;
                    } else { //11
                        Log.d(TAG_IPHC, "DAM 11: 0 bits.  The address is fully elided. first 64bits of the address are the link-local prefix " +
                                "padded with zeros. re remaining 64bits are computed form the encapsulating header.");
                        DAM = 3;
                    }

                } else {
                    Log.e(TAG_IPHC, "ERROR IPHC: DAM bits");
                    DAM = 5;
                }

            } else { //if DAC is 1
                if (getBit(secondByte, 1) == 0) { //0?
                    if (getBit(secondByte, 0) == 0) { //00
                        Log.d(TAG_IPHC, "DAM 00: RESERVED");
                        DAM = 0;
                    } else { //01
                        Log.d(TAG_IPHC, "DAM 01: 64 bits.  The address is derived using context information and the 64 bits carried in-line");
                        DAM = 1;
                    }

                } else if (getBit(secondByte, 1) > 0) { //1?
                    if (getBit(secondByte, 0) == 0) { //10
                        Log.d(TAG_IPHC, "DAM 10: 16 bits.  The address is derived using context information and the 16 bits carried in-line.  Bits covered by context" +
                                " information are always used.  Any IID bits not covered by context information are taken directly from their" +
                                " corresponding bits in the 16-bit to IID mapping given by 0000:00ff:fe00:XXXX, where XXXX are the 16 bits carried in-" +
                                " line.  Any remaining bits are zero.");
                        DAM = 2;
                    } else { //11
                        Log.d(TAG_IPHC, "DAM 11: 0 bits.  The address is fully elided and is derived using context information and the encapsulating header");
                        DAM = 3;
                    }

                } else {
                    Log.e(TAG_IPHC, "ERROR IPHC: DAM bits");
                    DAM = 5;
                }
            }
        } else { //M is 1
            if (DAC == false) { //if DAC is 0
                if (getBit(secondByte, 1) == 0) { //0?
                    if (getBit(secondByte, 0) == 0) { //00
                        Log.d(TAG_IPHC, "DAM 00: 128 bits, full address is carried in-line");
                        DAM = 0;
                    } else { //01
                        Log.d(TAG_IPHC, "DAM 01: 48 bits,  The address takes the form ffXX::00XX:XXXX:XXXX");
                        DAM = 1;
                    }

                } else if (getBit(secondByte, 1) > 0) { //1?
                    if (getBit(secondByte, 0) == 0) { //10
                        Log.d(TAG_IPHC, "DAM 10: 32 bits, the address takes the form ffXX::00XX:XXXX.");
                        DAM = 2;
                    } else { //11
                        Log.d(TAG_IPHC, "DAM 11: 8 bits.  The address takes the form ff02::00XX.");
                        DAM = 3;
                    }

                } else {
                    Log.e(TAG_IPHC, "ERROR IPHC: DAM bits");
                    DAM = 5;
                }

            } else { //if DAC is 1
                if (getBit(secondByte, 1) == 0) { //0?
                    if (getBit(secondByte, 0) == 0) { //00
                        Log.d(TAG_IPHC, "DAM 00: 48 bits.  This format is designed to match Unicast-Prefix-  based IPv6 Multicast Addresses as defined in [RFC3306] and\n" +
                                " [RFC3956].  The multicast address takes the form ffXX:XXLL:PPPP:PPPP:PPPP:PPPP:XXXX:XXXX. where the X are the nibbles\n" +
                                " that are carried in-line, in the order in which they appear in this format.  P denotes nibbles used to encode the prefix\n" +
                                " itself.  L denotes nibbles used to encode the prefix length. The prefix information P and L is taken from the specified context.");
                        DAM = 0;
                    } else { //01
                        Log.d(TAG_IPHC, "DAM 01: RESERVED");
                        DAM = 1;
                    }

                } else if (getBit(secondByte, 1) > 0) { //1?
                    if (getBit(secondByte, 0) == 0) { //10
                        Log.d(TAG_IPHC, "DAM 10: RESERVED");
                        DAM = 2;
                    } else { //11
                        Log.d(TAG_IPHC, "DAM 11: RESERVED");
                        DAM = 3;
                    }

                } else {
                    Log.e(TAG_IPHC, "ERROR IPHC: DAM bits");
                    DAM = 5;
                }
            }
        }
        Log.d("###", "-----------------------------------");
    }

    private int getBit(byte input, int position) {
        int res = ((byte) input) & (0x01 << position);

        return res;
    }
}
