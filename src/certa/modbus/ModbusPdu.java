package certa.modbus;

import static certa.modbus.ModbusConstants.*;

public class ModbusPdu {

	protected final byte[] pdu = new byte[MAX_PDU_SIZE]; // function (1 byte), data (0..252 bytes)
	protected int pduSize;
	
	public static final String toHex(byte[] data, int offset, int length) {
		if ((data.length == 0) || (offset > data.length) || (length < offset))
			return "";
		length = Math.min(data.length - offset, length);
		StringBuffer buf = new StringBuffer(length * 3);
		for (int i = 0; i < length; i++) {
			int b = data[i + offset] & 0xFF;
			buf.append(Integer.toHexString(b >>> 4));
			buf.append(Integer.toHexString(b & 0xF));
			if (i < length - 1)
				buf.append(" ");
		}
		return buf.toString();
	}

	public static final String byteToHex(byte b) {
		int t = b & 0xFF;
		return Integer.toHexString(t >>> 4) + Integer.toHexString(t & 0xF);
	}

	private static final int[] CrcTable = {
		0x0000, 0xC0C1, 0xC181, 0x0140, 0xC301, 0x03C0, 0x0280, 0xC241,
		0xC601, 0x06C0, 0x0780, 0xC741, 0x0500, 0xC5C1, 0xC481, 0x0440,
		0xCC01, 0x0CC0, 0x0D80, 0xCD41, 0x0F00, 0xCFC1, 0xCE81, 0x0E40,
		0x0A00, 0xCAC1, 0xCB81, 0x0B40, 0xC901, 0x09C0, 0x0880, 0xC841,
		0xD801, 0x18C0, 0x1980, 0xD941, 0x1B00, 0xDBC1, 0xDA81, 0x1A40,
		0x1E00, 0xDEC1, 0xDF81, 0x1F40, 0xDD01, 0x1DC0, 0x1C80, 0xDC41,
		0x1400, 0xD4C1, 0xD581, 0x1540, 0xD701, 0x17C0, 0x1680, 0xD641,
		0xD201, 0x12C0, 0x1380, 0xD341, 0x1100, 0xD1C1, 0xD081, 0x1040,
		0xF001, 0x30C0, 0x3180, 0xF141, 0x3300, 0xF3C1, 0xF281, 0x3240,
		0x3600, 0xF6C1, 0xF781, 0x3740, 0xF501, 0x35C0, 0x3480, 0xF441,
		0x3C00, 0xFCC1, 0xFD81, 0x3D40, 0xFF01, 0x3FC0, 0x3E80, 0xFE41,
		0xFA01, 0x3AC0, 0x3B80, 0xFB41, 0x3900, 0xF9C1, 0xF881, 0x3840,
		0x2800, 0xE8C1, 0xE981, 0x2940, 0xEB01, 0x2BC0, 0x2A80, 0xEA41,
		0xEE01, 0x2EC0, 0x2F80, 0xEF41, 0x2D00, 0xEDC1, 0xEC81, 0x2C40,
		0xE401, 0x24C0, 0x2580, 0xE541, 0x2700, 0xE7C1, 0xE681, 0x2640,
		0x2200, 0xE2C1, 0xE381, 0x2340, 0xE101, 0x21C0, 0x2080, 0xE041,
		0xA001, 0x60C0, 0x6180, 0xA141, 0x6300, 0xA3C1, 0xA281, 0x6240,
		0x6600, 0xA6C1, 0xA781, 0x6740, 0xA501, 0x65C0, 0x6480, 0xA441,
		0x6C00, 0xACC1, 0xAD81, 0x6D40, 0xAF01, 0x6FC0, 0x6E80, 0xAE41,
		0xAA01, 0x6AC0, 0x6B80, 0xAB41, 0x6900, 0xA9C1, 0xA881, 0x6840,
		0x7800, 0xB8C1, 0xB981, 0x7940, 0xBB01, 0x7BC0, 0x7A80, 0xBA41,
		0xBE01, 0x7EC0, 0x7F80, 0xBF41, 0x7D00, 0xBDC1, 0xBC81, 0x7C40,
		0xB401, 0x74C0, 0x7580, 0xB541, 0x7700, 0xB7C1, 0xB681, 0x7640,
		0x7200, 0xB2C1, 0xB381, 0x7340, 0xB101, 0x71C0, 0x7080, 0xB041,
		0x5000, 0x90C1, 0x9181, 0x5140, 0x9301, 0x53C0, 0x5280, 0x9241,
		0x9601, 0x56C0, 0x5780, 0x9741, 0x5500, 0x95C1, 0x9481, 0x5440,
		0x9C01, 0x5CC0, 0x5D80, 0x9D41, 0x5F00, 0x9FC1, 0x9E81, 0x5E40,
		0x5A00, 0x9AC1, 0x9B81, 0x5B40, 0x9901, 0x59C0, 0x5880, 0x9841,
		0x8801, 0x48C0, 0x4980, 0x8941, 0x4B00, 0x8BC1, 0x8A81, 0x4A40,
		0x4E00, 0x8EC1, 0x8F81, 0x4F40, 0x8D01, 0x4DC0, 0x4C80, 0x8C41,
		0x4400, 0x84C1, 0x8581, 0x4540, 0x8701, 0x47C0, 0x4680, 0x8641,
		0x8201, 0x42C0, 0x4380, 0x8341, 0x4100, 0x81C1, 0x8081, 0x4040,
	};

	public static final int calcCRC16(byte[] data, int offset, int length) {
		int crc = 0xFFFF;
		for (int i = 0; i < length; i++) {
			crc = (crc >>> 8) ^ CrcTable[(crc ^ data[offset + i]) & 0xff];
		}
		return crc;
	}

	public static final int bytesToInt16(byte lowByte, byte highByte, boolean unsigned) {
		// returned value is signed
		int i = (((int) highByte) << 8) | (((int) lowByte) & 0xFF);
		if (unsigned)
			return i & 0xFFFF;
		else
			return i;
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
	
	public static final int bytesCount(int bitsCount) {
		int bytes = bitsCount / 8;
		if ((bitsCount % 8) != 0)
			bytes++;
		return bytes;
	}	

	public void setPduSize(int size) {
		if ((size < 1) || (size > MAX_PDU_SIZE))
			throw new IllegalArgumentException("Invalid PDU size: " + size);
		pduSize = size;
	}

	public int getPduSize() {
		return pduSize;
	}
	
	public int getFunction() {
		return readByteFromPDU(0, true);
	}

	public void setFunction(int code) {
		writeByteToPDU(0, (byte) code);
	}

	public void readFromPdu(int pduOffset, int size, byte[] dest, int destOffset) {
		System.arraycopy(pdu, pduOffset, dest, destOffset, size);
	}
	
	public void writeToPdu(byte[] src, int srcOffset, int size, int pduOffset) {
		System.arraycopy(src, srcOffset, pdu, pduOffset, size);
	}
	
	public void writeByteToPDU(int offset, byte value) {
		if ((offset < 0) || (offset >= pduSize))
			throw new IndexOutOfBoundsException();
		pdu[offset] = value;
	}

	public void writeInt16ToPDU(int offset, int value) {
		// We can only write words starting from offset 1, because there is function code at offset 0.
		if ((offset < 1) || (offset >= pduSize - 1))
			throw new IndexOutOfBoundsException();
		// Modbus uses a "big-Endian" representation (the most significant byte is sent first).
		pdu[offset] = highByte(value);
		pdu[offset + 1] = lowByte(value);
	}

	public void writeBitToPDU(int firstByte, int bitOffset, boolean value) {
		int offset = firstByte + (bitOffset / 8);
		byte b = readByteFromPDU(offset);
		if (value)
			b = (byte)(b | (1 << (bitOffset % 8)));
		else
			b = (byte)(b & ~(1 << (bitOffset % 8)));
		writeByteToPDU(offset, b);
	}
	
	public int readByteFromPDU(int offset, boolean unsigned) {
		if (unsigned)
			return ((int) readByteFromPDU(offset)) & 0xFF;
		else
			return readByteFromPDU(offset);
	}

	public byte readByteFromPDU(int offset) {
		if ((offset < 0) || (offset >= pduSize))
			throw new IndexOutOfBoundsException();
		return pdu[offset];
	}

	public int readInt16FromPDU(int offset, boolean unsigned) {
		// Integers can be placed only in DATA section of PDU (starting from offset 1) 
		if ((offset < 1) || (offset >= pduSize - 1))
			throw new IndexOutOfBoundsException();
		// Big-endian is standard for MODBUS
		return bytesToInt16(pdu[offset + 1], pdu[offset], unsigned);
	}

	protected int readInt32FromPDU(int offset, boolean bigEndian) {
		if (bigEndian)
			// this is "big-endian" (0x12345678 stored as 0x12, 0x34, 0x56, 0x78)
			return ints16ToInt32(readInt16FromPDU(offset + 2, false), readInt16FromPDU(offset, false));
		else
			// this is "middle-endian" (0x12345678 stored as 0x56, 0x78, 0x12, 0x34)
			return ints16ToInt32(readInt16FromPDU(offset, false), readInt16FromPDU(offset + 2, false));
	}

	protected float readFloatFromPDU(int offset, boolean bigEndian) {
		return Float.intBitsToFloat(readInt32FromPDU(offset, bigEndian));
	}

	public boolean readBitFromPDU(int firstByte, int bitOffset) {
		byte b = readByteFromPDU(firstByte + (bitOffset / 8));
		return (b & (1 << (bitOffset % 8))) != 0;
	}
	
}
