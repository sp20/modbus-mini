package ua.com.certa.modbus;

import java.io.IOException;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gnu.io.CommPortIdentifier;
import gnu.io.NoSuchPortException;
import gnu.io.PortInUseException;
import gnu.io.SerialPort;
import gnu.io.UnsupportedCommOperationException;

public class ModbusRtuClient extends AModbusClient {
	private static final Logger log = LoggerFactory.getLogger(ModbusRtuClient.class);

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

	public ModbusRtuClient(String portName, int baudRate, int dataBits, int parity, int stopBits, int timeout, int pause) {
		this.portName = portName;
		this.baudrate = baudRate;
		this.dataBits = dataBits;
		this.parity = parity;
		this.stopBits = stopBits;
		this.timeout = timeout;
		this.pause = pause;
	}

	private void openPort() throws NoSuchPortException, PortInUseException, UnsupportedCommOperationException {
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
	protected void sendRequest() throws NoSuchPortException, PortInUseException, UnsupportedCommOperationException, IOException {
		openPort();
		if (pause > 0)
			try {
				Thread.sleep(pause);
			} catch (InterruptedException e) {
				// ignore
			}
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
	
	@Override
	protected int waitResponse() throws NoSuchPortException, PortInUseException, UnsupportedCommOperationException, IOException {
		openPort();
		expectedBytes = getExpectedPduSize() + 3; // id(1), PDU(n), crc(2)
		
		// read id
		if (!readToBuffer(0, 1))
			return RESULT_TIMEOUT;
		if (buffer[0] != getServerId()) {
			log.warn("waitResponse(): Invalid id: {} (expected: {})", buffer[0], getServerId());
			return RESULT_BAD_RESPONSE;
		}

		// read function (bit7 means exception)
		if (!readToBuffer(1, 1))
			return RESULT_TIMEOUT;
		if ((buffer[1] & 0x7f) != getFunction()) {
			log.warn("waitResponse(): Invalid function: {} (expected: {})", buffer[1], getFunction());
			return RESULT_BAD_RESPONSE;
		}
		
		if ((buffer[1] & 0x80) != 0) {
			// EXCEPTION
			expectedBytes = 5; // id(1), function(1), exception code(1), crc(2)
			if (!readToBuffer(2, 3)) // exception code + CRC
				return RESULT_TIMEOUT;
			if (log.isTraceEnabled())
				log.trace("Read (exception): " + ModbusUtils.toHex(buffer, 0, expectedBytes));
			if (crcValid(3)) {
				pduSize = 2; // function + exception code
				System.arraycopy(buffer, 1, pdu, 0, pduSize);
				return RESULT_EXCEPTION;
			}
			else {
				return RESULT_BAD_RESPONSE;
			}
		}
		else {
			// NORMAL RESPONSE
			if (!readToBuffer(2, getExpectedPduSize() + 1)) // data + CRC (without function)
				return RESULT_TIMEOUT;
			if (log.isTraceEnabled())
				log.trace("Read (normal): " + ModbusUtils.toHex(buffer, 0, expectedBytes));
			// CRC check of (serverId + PDU)
			if (crcValid(1 + getExpectedPduSize())) {
				pduSize = getExpectedPduSize();
				System.arraycopy(buffer, 1, pdu, 0, pduSize);
				return RESULT_OK;
			}
			else
				return RESULT_BAD_RESPONSE;
		}
	}

	@Override
	public void close() {
		SerialPort t = port;
		port = null;
		if (t != null) {
			log.info("Closing port: " + t.getName());
			t.close();
			log.info("Port closed: " + portName);
		}
	}

}
