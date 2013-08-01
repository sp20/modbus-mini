package ua.com.certa.modbus;

import java.io.Closeable;

public abstract class AModbus implements Closeable {
	public static final int MAX_PDU_SIZE = 253;
	public static final int MAX_READ_COILS = 2000;
	public static final int MAX_READ_REGS = 125;
	public static final int MAX_WRITE_COILS = 1968;
	public static final int MAX_WRITE_REGS = 123;

	public static final byte FN_READ_COILS = 1;
	public static final byte FN_READ_DISCRETE_INPUTS = 2;
	public static final byte FN_READ_HOLDING_REGISTERS = 3;
	public static final byte FN_READ_INPUT_REGISTERS = 4;
	public static final byte FN_WRITE_SINGLE_COIL = 5;
	public static final byte FN_WRITE_SINGLE_REGISTER = 6;
	public static final byte FN_WRITE_MULTIPLE_COILS = 15;
	public static final byte FN_WRITE_MULTIPLE_REGISTERS = 16;

	protected final byte[] pdu = new byte[MAX_PDU_SIZE]; // function (1 byte), data (0..252 bytes)
	protected int pduSize;
	
	public static final int toUnsigned16(int int16)
	{
		return int16 & 0xFFFF;
	}

	public static final int bytesToInt16(byte lowByte, byte highByte) {
		// returned value is signed
		return (highByte << 8) | (lowByte & 0xFF); 
	}

	public static final int ints16ToInt32(int lowInt16, int highInt16) {
		return (highInt16 << 16) | (lowInt16 & 0xFFFF); 
	}

	public static final float ints16ToFloat(int lowInt16, int highInt16) {
		return Float.intBitsToFloat(ints16ToInt32(lowInt16, highInt16)); 
	}

	public static final byte highByte(int int16) {
		return (byte)(int16 >>> 8); 
	}

	public static final byte lowByte(int int16) {
		return (byte)(int16); 
	}

	public static final int highInt16(int int32) {
		// returned value is signed
		return int32 >> 16; 
	}

	public static final int lowInt16(int int32) {
		// returned value is signed
		return ((int32 & 0xFFFF) << 16) >> 16; 
	}

	public static final boolean[] int16ToBits(int int16)
	{
		boolean[] bits = new boolean[16];
	    for (int i = 0; i < 16; i++, int16 >>= 1)
	    	bits[i] = (int16 & 1) != 0;
	    return bits;
	}
	
	protected static final int bytesCount(int bitsCount) {
		int bytes = bitsCount / 8;
		if ((bitsCount % 8) != 0)
			bytes++;
		return bytes;
	}	

	protected void setPduSize(int size) {
		if ((size < 1) || (size > MAX_PDU_SIZE))
			throw new IllegalArgumentException("Invalid PDU size: " + size);
		pduSize = size;
	}

	protected int getPduSize() {
		return pduSize;
	}
	
	protected void writeByteToPDU(int offset, byte value) {
		if ((offset < 0) || (offset >= pduSize))
			throw new IndexOutOfBoundsException();
		pdu[offset] = value;
	}

	protected void writeInt16ToPDU(int offset, int value) {
		if ((offset < 1) || (offset >= pduSize - 1))
			throw new IndexOutOfBoundsException();
		pdu[offset] = highByte(value);
		pdu[offset + 1] = lowByte(value);
	}

	protected byte readByteFromPDU(int offset) {
		if ((offset < 0) || (offset >= pduSize))
			throw new IndexOutOfBoundsException();
		return pdu[offset];
	}

	protected int readInt16FromPDU(int offset) {
		// Integers can be placed only in DATA section of PDU (starting from offset 1) 
		if ((offset < 1) || (offset >= pduSize - 1))
			throw new IndexOutOfBoundsException();
		// Big-endian is standard for MODBUS
		return bytesToInt16(pdu[offset + 1], pdu[offset]);
	}

	protected int readInt32FromPDU(int offset, boolean bigEndian) {
		if (bigEndian)
			// this is "big-endian" (0x12345678 stored as 0x12, 0x34, 0x56, 0x78)
			return ints16ToInt32(readInt16FromPDU(offset + 2), readInt16FromPDU(offset));
		else
			// this is "middle-endian" (0x12345678 stored as 0x56, 0x78, 0x12, 0x34)
			return ints16ToInt32(readInt16FromPDU(offset), readInt16FromPDU(offset + 2));
	}

	protected float readFloatFromPDU(int offset, boolean bigEndian) {
		return Float.intBitsToFloat(readInt32FromPDU(offset, bigEndian));
	}

}
