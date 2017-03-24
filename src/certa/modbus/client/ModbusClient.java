package certa.modbus.client;

import static certa.modbus.ModbusConstants.*;

import java.io.Closeable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import certa.modbus.ModbusPdu;

public class ModbusClient extends ModbusPdu implements Closeable {
	private static final Logger log = LoggerFactory.getLogger(ModbusClient.class);

	public static final byte RESULT_OK = 0;
	public static final byte RESULT_TIMEOUT = 1;
	public static final byte RESULT_EXCEPTION = 2; // Modbus exception. Get code by getExceptionCode() 
	public static final byte RESULT_BAD_RESPONSE = 3; // CRC mismatch, or invalid format

	private boolean requestReady = false;
	private boolean responseReady = false;
	private byte srvId;
	private int expectedPduSize;
	private int expectedAddress = -1;
	private int expectedCount = -1;
	private int result; // RESULT_*

	private ModbusClientTransport transport;
	
	public void setTransport(ModbusClientTransport tr) {
		this.transport = tr;
	}
	
	public byte getServerId() {
		return srvId;
	}

	protected int getExpectedPduSize() {
		return expectedPduSize;
	}

	protected void initCustomRequest(int newServerId, int newPduSize, byte function, int newExpectedPduSize) 
	{
		setPduSize(newPduSize);
		writeByteToPDU(0, function);
		this.srvId = (byte)newServerId;
		this.expectedAddress = -1;
		this.expectedCount = -1;
		this.expectedPduSize = newExpectedPduSize;
		this.requestReady = true;
		this.responseReady = false;
	}

	protected void initRequest(int newServerId, int newPduSize, byte function, int param1, int param2, 
			int newExpectedAddress, int newExpectedCount, int newExpectedPduSize) 
	{
		setPduSize(newPduSize);
		writeByteToPDU(0, function);
		writeInt16ToPDU(1, param1);
		writeInt16ToPDU(3, param2);
		this.srvId = (byte)newServerId;
		this.expectedAddress = newExpectedAddress;
		this.expectedCount = newExpectedCount;
		this.expectedPduSize = newExpectedPduSize;
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

	public boolean execRequest() throws Exception {
		if (!requestReady)
			throw new IllegalStateException("Call InitXXXRequest() first.");
		if (transport == null)
			throw new IllegalStateException("Transport is not set.");
		transport.sendRequest(this);
		requestReady = false;
		result = transport.waitResponse(this);
		responseReady = (result == RESULT_OK);
		if (!responseReady && (log != null) && log.isWarnEnabled()) {
			if (result == RESULT_EXCEPTION)
				log.warn("Exception 0x{} from {}", byteToHex((byte) getExceptionCode()), getServerId());
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

	public int getExceptionCode() {
		if (((getFunction() & 0x80) == 0) || (getPduSize() < 2))
			return 0;
		else
			return readByteFromPDU(1, true);
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
	 * There are various utility methods in {@link ModbusPdu} to manipulate int16 values.
	 * @param address - Address of register. It must be in the range specified in request.
	 * You can use {@link #getResponseAddress()} and {@link #getResponseCount()}.
	 * @param unsigned - Should value stored in PDU be interpreted as signed or unsigned.
	 * @return Value of register at given address. This value is 16 bit signed (-32768..+32767) or  
	 * 16 bit unsigned (0..65535) depending on <b>unsigned</b> parameter.
	 */
	public int getResponseRegister(int address, boolean unsigned) {
		if ((getFunction() == FN_READ_HOLDING_REGISTERS) || (getFunction() == FN_READ_INPUT_REGISTERS)) {
			int offset = address - getResponseAddress();
			if ((offset < 0) || (offset >= getResponseCount()))
				throw new IndexOutOfBoundsException();
			return readInt16FromPDU(2 + offset * 2, unsigned);
		}
		else
			throw new IllegalStateException();
	}

	@Override
	public void close() {
		if (transport != null)
			transport.close();
	}
}
