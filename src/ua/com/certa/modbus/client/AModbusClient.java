package ua.com.certa.modbus.client;

import java.io.Closeable;

import org.slf4j.Logger;

import ua.com.certa.modbus.AModbus;
import ua.com.certa.modbus.ModbusUtils;

public abstract class AModbusClient extends AModbus implements Closeable {

	public static final byte RESULT_OK = 0;
	public static final byte RESULT_TIMEOUT = 1;
	public static final byte RESULT_EXCEPTION = 2; // Modbus exception. Get code by getExceptionCode() 
	public static final byte RESULT_BAD_RESPONSE = 3; // CRC, or invalid format

	private boolean requestReady = false;
	private boolean responseReady = false;
	private byte serverId;
	private int expectedPduSize;
	private int expectedAddress = -1;
	private int expectedCount = -1;
	private int result; // RESULT_*

	private Logger log = getLog();
	
	public byte getServerId() {
		return serverId;
	}

	protected int getExpectedPduSize() {
		return expectedPduSize;
	}

	private void initRequest(int serverId, int pduSize, byte function, int param1, int param2, 
			int expectedAddress, int expectedCount, int expectedPduSize) 
	{
		setPduSize(pduSize);
		writeByteToPDU(0, function);
		writeInt16ToPDU(1, param1);
		writeInt16ToPDU(3, param2);
		this.serverId = (byte)serverId;
		this.expectedAddress = expectedAddress;
		this.expectedCount = expectedCount;
		this.expectedPduSize = expectedPduSize;
		this.requestReady = true;
		this.responseReady = false;
	}

	public void InitReadCoilsRequest(int serverId, int startAddress, int count) {
		if ((count < 1) || (count > MAX_READ_COILS))
			throw new IllegalArgumentException();
		initRequest(serverId, 5, FN_READ_COILS, startAddress, count, 
				startAddress, count, 2 + bytesCount(count));
	}

	public void InitReadDInputsRequest(int serverId, int startAddress, int count) {
		if ((count < 1) || (count > MAX_READ_COILS))
			throw new IllegalArgumentException();
		initRequest(serverId, 5, FN_READ_DISCRETE_INPUTS, startAddress, count, 
				startAddress, count, 2 + bytesCount(count));
	}

	public void InitReadHoldingsRequest(int serverId, int startAddress, int count) {
		if ((count < 1) || (count > MAX_READ_REGS))
			throw new IllegalArgumentException();
		initRequest(serverId, 5, FN_READ_HOLDING_REGISTERS, startAddress, count, 
				startAddress, count, 2 + count * 2);
	}

	public void InitReadAInputsRequest(int serverId, int startAddress, int count) {
		if ((count < 1) || (count > MAX_READ_REGS))
			throw new IllegalArgumentException();
		initRequest(serverId, 5, FN_READ_INPUT_REGISTERS, startAddress, count, 
				startAddress, count, 2 + count * 2);
	}

	public void InitWriteCoilRequest(int serverId, int coilAddress, boolean value) {
		initRequest(serverId, 5, FN_WRITE_SINGLE_COIL, coilAddress, value ? 0xFF00 : 0, -1, -1, 5);
	}

	public void InitWriteRegisterRequest(int serverId, int regAddress, int value) {
		initRequest(serverId, 5, FN_WRITE_SINGLE_REGISTER, regAddress, value, -1, -1, 5);
	}

	public void InitWriteCoilsRequest(int serverId, int startAddress, boolean[] values) {
		if (values.length > MAX_WRITE_COILS)
			throw new IllegalArgumentException();
		int bytes = bytesCount(values.length);
		initRequest(serverId, 6 + bytes, FN_WRITE_MULTIPLE_COILS, startAddress, values.length, -1, -1, 5);
		writeByteToPDU(5, (byte)bytes);
		for (int i = 0; i < bytes; i++) {
			byte b = 0;
			for (int j = 0; j < 8; j++) {
				int k = i * 8 + j;
				if ((k < values.length) && values[k])
					b = (byte) (b | (1 << j));
			}
			writeByteToPDU(6 + i, b);
		}
	}

	public void InitWriteRegistersRequest(int serverId, int startAddress, int[] values) {
		if (values.length > MAX_WRITE_REGS)
			throw new IllegalArgumentException();
		int bytes = values.length * 2;
		initRequest(serverId, 6 + bytes, FN_WRITE_MULTIPLE_REGISTERS, startAddress, values.length, -1, -1, 5);
		writeByteToPDU(5, (byte)bytes);
		for (int i = 0; i < values.length; i++) {
			writeInt16ToPDU(6 + i * 2, values[i]);
		}
	}

	protected abstract Logger getLog();
	protected abstract void sendRequest() throws Exception;
	protected abstract int waitResponse() throws Exception; // returns RESULT_*

	public boolean execRequest() throws Exception {
		if (!requestReady)
			throw new IllegalStateException("Call InitXXXRequest() first.");
		sendRequest();
		requestReady = false;
		result = waitResponse();
		responseReady = (result == RESULT_OK);
		if (!responseReady && (log != null) && log.isWarnEnabled()) {
			if (result == RESULT_EXCEPTION)
				log.warn("Exception 0x{} from {}", ModbusUtils.byteToHex(getExceptionCode()), getServerId());
			else
				log.warn(getResultAsString() + " from {}", getServerId());
		}
		return responseReady;

	}

	public int getResult() {
		return result;
	}

	public String getResultAsString() {
		switch (result) {
		case RESULT_OK:
			return "OK";
		case RESULT_BAD_RESPONSE:
			return "Bad response";
		case RESULT_EXCEPTION:
			return "Exception " + getExceptionCode();
		case RESULT_TIMEOUT:
			return "Timeout";
		default:
			return null;
		}
	}

	protected int getFunction() {
		return readByteFromPDU(0);
	}

	public byte getExceptionCode() {
		if ((getFunction() & 0x80) == 0)
			return 0;
		else
			return readByteFromPDU(1);
	}

	public int getResponseAddress() {
		if (responseReady && (expectedAddress >= 0)) 
			return (expectedAddress);
		else
			throw new IllegalStateException();
	}

	public int getResponseCount() {
		if (responseReady && (expectedCount >= 0)) 
			return (expectedCount);
		else
			throw new IllegalStateException();
	}

	/**
	 * Get discrete value from response to request initiated by {@link #InitReadCoilsRequest()} or 
	 * {@link #InitReadDInputsRequest()}. Call this method ONLY after successful execution 
	 * of {@link #execRequest()}.<br>
	 * @param address - Address of bit. It must be in the range specified in request.
	 * You can use {@link #getResponseAddress()} and {@link #getResponseCount()}.
	 * @return Value of bit at given address.
	 */
	public boolean getResponseBit(int address) {
		if ((getFunction() == FN_READ_COILS) || (getFunction() == FN_READ_DISCRETE_INPUTS)) {
			int offset = address - getResponseAddress();
			if ((offset < 0) || (offset >= getResponseCount()))
				throw new IndexOutOfBoundsException();
			byte b = readByteFromPDU(2 + offset / 8);
			return (b & (1 << (offset % 8))) != 0;
		}
		else
			throw new IllegalStateException();
	}

	/**
	 * Get register value from response to request initiated by {@link #InitReadHoldingsRequest()} or 
	 * {@link #InitReadAInputsRequest()}. Call this method ONLY after successful execution 
	 * of {@link #execRequest()}.<br>
	 * There are various utility methods in {@link AModbus} to manipulate int16 values.
	 * @param address - Address of register. It must be in the range specified in request.
	 * You can use {@link #getResponseAddress()} and {@link #getResponseCount()}.
	 * @return Value of register at given address. This value is 16 bit signed (-32768..+32767).  
	 * To make it unsigned (0..65535) use {@link AModbus#makeUnsigned16()}.
	 */
	public int getResponseRegister(int address) {
		if ((getFunction() == FN_READ_HOLDING_REGISTERS) || (getFunction() == FN_READ_INPUT_REGISTERS)) {
			int offset = address - getResponseAddress();
			if ((offset < 0) || (offset >= getResponseCount()))
				throw new IndexOutOfBoundsException();
			return readInt16FromPDU(2 + offset * 2);
		}
		else
			throw new IllegalStateException();
	}

}
