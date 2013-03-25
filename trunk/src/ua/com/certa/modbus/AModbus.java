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
	
	public static final int bytesToInt16(byte low, byte high) {
		return ((((int)high) & 0xFF) << 8) | (((int)low) & 0xFF); 
	}

	public static final byte highByte(int int16) {
		return (byte)(int16 >>> 8); 
	}

	public static final byte lowByte(int int16) {
		return (byte)(int16); 
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
		if ((offset < 1) || (offset >= pduSize - 1))
			throw new IndexOutOfBoundsException();
		return bytesToInt16(pdu[offset + 1], pdu[offset]);
	}

}
