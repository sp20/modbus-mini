package ua.com.certa.modbus.client;

import java.io.IOException;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ua.com.certa.modbus.ModbusUtils;
import ua.com.certa.modbus.client.AModbusClient;
import gnu.io.CommPortIdentifier;
import gnu.io.NoSuchPortException;
import gnu.io.PortInUseException;
import gnu.io.SerialPort;
import gnu.io.UnsupportedCommOperationException;

public class ModbusRtuClientRxtx extends AModbusClient {
	private static final Logger log = LoggerFactory.getLogger(ModbusRtuClientRxtx.class);

	private SerialPort port;
	private final String portName;
	private final int baudrate;
	private final int dataBits;
	private final int parity;
	private final int stopBits;
	private final int timeout;
	private final int pause;
	
	private final byte[] buffer = new byte[MAX_PDU_SIZE + 3]; // ADU: [ID(1), PDU(n), CRC(2)]
	private int expectedBytes; // for logging

	public ModbusRtuClientRxtx(String portName, int baudRate, int dataBits, int parity, int stopBits, int timeout, int pause) {
		this.portName = portName;
		this.baudrate = baudRate;
		this.dataBits = dataBits;
		this.parity = parity;
		this.stopBits = stopBits;
		this.timeout = timeout;
		this.pause = pause;
	}

	// this method must be synchronized with close()
	synchronized private void openPort() throws NoSuchPortException, PortInUseException, UnsupportedCommOperationException {
		if (port != null)
			return;
		log.info("Opening port: " + portName);
		CommPortIdentifier ident = CommPortIdentifier.getPortIdentifier(portName);
		port = ident.open("ModbusRtuClient on " + portName, 2000);
		port.setOutputBufferSize(buffer.length);
		port.setInputBufferSize(buffer.length);
		try {
			port.setSerialPortParams(baudrate, dataBits, stopBits, parity);				
			port.enableReceiveTimeout(timeout);
			port.setFlowControlMode(SerialPort.FLOWCONTROL_NONE);
		} catch (UnsupportedCommOperationException e) {
			close();
			throw e;
		}
		log.info("Port opened: " + port.getName());
	}

	// this method may be called in other thread
	@Override
	synchronized public void close() {
		SerialPort t = port;
		port = null;  // this may lead to NullPointerException in parallel thread, but I can't see good solution
		if (t != null) {
			log.info("Closing port: " + t.getName());
			t.close();
			log.info("Port closed: " + portName);
		}
	}

	public void clearInput() throws IOException {
		InputStream in = port.getInputStream();
		int avail = in.available();
		while (avail > 0) {
			int count = Math.min(avail, buffer.length);
			in.read(buffer, 0, count);
			if (log.isWarnEnabled())
				log.warn("Unexpected input: " + ModbusUtils.toHex(buffer, 0, count));
			avail = in.available();
		}
	}

	@Override
	protected Logger getLog() {
		return log;
	}
	
	@Override
	protected void sendRequest() throws NoSuchPortException, PortInUseException, UnsupportedCommOperationException, IOException, InterruptedException {
		if (pause > 0)
			Thread.sleep(pause);
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
		port.getOutputStream().write(buffer, 0, size);
		port.getOutputStream().flush();
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
	
	private boolean readToBuffer(int start, int length) throws IOException {
		InputStream in = port.getInputStream();
		long now = System.currentTimeMillis();
		long deadline = now + timeout;
		int offset = start;
		int bytesToRead = length;
		int res;
		while ((now < deadline) && (bytesToRead > 0)) {
			res = in.read(buffer, offset, bytesToRead);
			if (res < 0)
				break;
			offset += res;
			bytesToRead -= res;
			if (bytesToRead > 0) // only to avoid redundant call of System.currentTimeMillis()
				now = System.currentTimeMillis();
		}
		res = length - bytesToRead; // total bytes read
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
	
	// returns RESULT_*
	public int readIdToBuffer(byte expected) throws IOException {
		InputStream in = port.getInputStream();
		long now = System.currentTimeMillis();
		long deadline = now + timeout;
		int res = 0;
		while (now < deadline) {
			res = in.read(buffer, 0, 1);
			if (res >= 0) {
				if (buffer[0] == expected)
					return RESULT_OK;
				else {
					logData("bad id", 0, 1);
					//log.warn("Unexpected id: {} (need {})", buffer[0], expected);
				}
			}
		}
		return RESULT_TIMEOUT;
	}

	@Override
	protected int waitResponse() throws NoSuchPortException, PortInUseException, UnsupportedCommOperationException, IOException {
		openPort();
		expectedBytes = getExpectedPduSize() + 3; // id(1), PDU(n), crc(2)
		
		// read id
		int r = readIdToBuffer(getServerId());
		if (r != RESULT_OK)
			return r;

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

}
