package ua.com.certa.modbus.client;

import java.util.Arrays;

import jssc.SerialPort;
import jssc.SerialPortException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ua.com.certa.modbus.ModbusUtils;

public class ModbusRtuClientJssc extends AModbusClient {
	private static final Logger log = LoggerFactory.getLogger(ModbusRtuClientJssc.class);

	private SerialPort port;
	private final String portName;
	private final int baudRate;
	private final int dataBits;
	private final int parity;
	private final int stopBits;
	private final int timeout;
	private final int pause;
	
	private final byte[] buffer = new byte[MAX_PDU_SIZE + 3]; // ADU: [ID(1), PDU(n), CRC(2)]
	private int expectedBytes; // for logging

	public ModbusRtuClientJssc(String portName, int baudRate, int dataBits, int parity, int stopBits, int timeout, int pause) {
		this.portName = portName;
		this.baudRate = baudRate;
		this.dataBits = dataBits;
		this.parity = parity;
		this.stopBits = stopBits;
		this.timeout = timeout;
		this.pause = pause;
	}

	private void openPort() throws SerialPortException {
		if (port != null)
			return;
		log.info("Opening port: {}", portName);
		port = new SerialPort(portName);
		try {
			port.openPort();
	        port.setParams(baudRate, dataBits, stopBits, parity);
		} catch (SerialPortException e) {
			close();
			throw e;
		}
		log.info("Port opened: {}", port.getPortName());
	}

	public void clearInput() throws SerialPortException {
		byte[] buf = port.readBytes();
		while (buf != null) {
			if (log.isWarnEnabled())
				log.warn("Unexpected input: " + ModbusUtils.toHex(buf, 0, buf.length));
			buf = port.readBytes();
		}
	}

	@Override
	protected Logger getLog() {
		return log;
	}
	
	@Override
	protected void sendRequest() throws SerialPortException {
		if (pause > 0)
			try {
				Thread.sleep(pause);
			} catch (InterruptedException e) {
				// ignore
			}
		openPort();
		clearInput();
		buffer[0] = getServerId();
		System.arraycopy(pdu, 0, buffer, 1, pduSize);
		int size = pduSize + 1;
		int crc = ModbusUtils.calcCRC16(buffer, 0, size);
		buffer[size] = lowByte(crc);
		buffer[size + 1] = highByte(crc);
		size = size + 2;
		if (log.isTraceEnabled())
			log.trace("Write: " + ModbusUtils.toHex(buffer, 0, size));
		port.writeBytes(Arrays.copyOfRange(buffer, 0, size));
	}

	private boolean crcValid(int size) {
		int crc = ModbusUtils.calcCRC16(buffer, 0, size);
		int crc2 = bytesToInt16(buffer[size], buffer[size + 1]) & 0xFFFF; 
		if (crc == crc2)
			return true;
		else {
			if (log.isWarnEnabled())
				log.warn("CRC error (calc: {}, in response: {})", Integer.toHexString(crc), Integer.toHexString(crc2));
			return false;
		}
	}
	
	private boolean readToBuffer(int start, int length) throws SerialPortException, InterruptedException {
		long now = System.currentTimeMillis();
		long deadline = now + timeout;
		int offset = start;
		int bytesToRead = length;
		while ((now < deadline) && (bytesToRead > 0)) {
			int avail = Math.min(port.getInputBufferBytesCount(), bytesToRead);
			if (avail > 0) {
				byte[] buf = port.readBytes(avail);
				avail = buf.length;
				System.arraycopy(buf, 0, buffer, offset, avail);
				offset += avail;
				bytesToRead -= avail;
			}
			if (bytesToRead > 0) {
				Thread.sleep(100);
				now = System.currentTimeMillis();
			}
		}
		int res = length - bytesToRead; // total bytes read
		if (res < length) {
			if ((res > 0) && log.isTraceEnabled())
				log.trace("Read (incomplete): " + ModbusUtils.toHex(buffer, 0, start + res));
			log.warn("Response from {} timeout ({} bytes, need {})", getServerId(), start + res, expectedBytes);
			return false;
		}
		else
			return true;
	}
	
	private void logData(String kind, int start, int length) {
		if (log.isTraceEnabled()) 
			log.trace("Read ({}): {}", kind, ModbusUtils.toHex(buffer, start, length));
	}
	
	@Override
	protected int waitResponse() throws SerialPortException, InterruptedException  {
		openPort();
		expectedBytes = getExpectedPduSize() + 3; // id(1), PDU(n), crc(2)
		
		// read id
		if (!readToBuffer(0, 1))
			return RESULT_TIMEOUT;
		if (buffer[0] != getServerId()) {
			logData("bad id", 0, 1);
			log.warn("waitResponse(): Invalid id: {} (expected: {})", buffer[0], getServerId());
			return RESULT_BAD_RESPONSE;
		}

		// read function (bit7 means exception)
		if (!readToBuffer(1, 1))
			return RESULT_TIMEOUT;
		if ((buffer[1] & 0x7f) != getFunction()) {
			logData("bad function", 0, 2);
			log.warn("waitResponse(): Invalid function: {} (expected: {})", buffer[1], getFunction());
			return RESULT_BAD_RESPONSE;
		}
		
		if ((buffer[1] & 0x80) != 0) {
			// EXCEPTION
			expectedBytes = 5; // id(1), function(1), exception code(1), crc(2)
			if (!readToBuffer(2, 3)) // exception code + CRC
				return RESULT_TIMEOUT;
			if (crcValid(3)) {
				logData("exception", 0, expectedBytes);
				pduSize = 2; // function + exception code
				System.arraycopy(buffer, 1, pdu, 0, pduSize);
				return RESULT_EXCEPTION;
			}
			else {
				logData("bad crc (exception)", 0, expectedBytes);
				return RESULT_BAD_RESPONSE;
			}
		}
		else {
			// NORMAL RESPONSE
			if (!readToBuffer(2, getExpectedPduSize() + 1)) // data + CRC (without function)
				return RESULT_TIMEOUT;
			// CRC check of (serverId + PDU)
			if (crcValid(1 + getExpectedPduSize())) {
				logData("normal", 0, expectedBytes);
				pduSize = getExpectedPduSize();
				System.arraycopy(buffer, 1, pdu, 0, pduSize);
				return RESULT_OK;
			}
			else {
				logData("bad crc", 0, expectedBytes);
				return RESULT_BAD_RESPONSE;
			}
		}
	}

	@Override
	public void close() {
		SerialPort t = port;
		port = null;
		if ((t != null) && (t.isOpened())) {
			log.info("Closing port: {}", portName);
			try {
				t.closePort();
			} catch (SerialPortException e) {
				log.error("Error closing port {}. {}", portName, e);
			}
			log.info("Port {} closed", portName);
		}
	}

}
