package certa.modbus.client;

import static certa.modbus.ModbusConstants.*;

import org.slf4j.Logger;
import certa.modbus.ModbusPdu;

public abstract class AbstractRtuTransport implements ModbusClientTransport {
	protected final Logger log;
	protected final int timeout;
	protected final int pause;
	protected final boolean keepConnection; 
	protected final byte[] buffer = new byte[MAX_PDU_SIZE + 3]; // ADU: [ID(1), PDU(n), CRC(2)]
	protected int expectedBytes; // for logging
	
	public AbstractRtuTransport(int timeout, int pause, boolean keepConnection, Logger log) {
		this.log = log;
		this.timeout = timeout;
		this.pause = pause;
		this.keepConnection = keepConnection;
	}

	protected abstract boolean openPort() throws Exception;
	protected abstract void clearInput() throws Exception;
	protected abstract void sendData(int size) throws Exception;
	protected abstract boolean readToBuffer(int start, int length, ModbusClient modbusClient) throws Exception;

	@Override
	public void sendRequest(ModbusClient modbusClient) throws Exception {
		if (pause > 0)
			Thread.sleep(pause);
		if (!openPort())
			return;
		clearInput();
		buffer[0] = modbusClient.getServerId();
		modbusClient.readFromPdu(0, modbusClient.getPduSize(), buffer, 1);
		int size = modbusClient.getPduSize() + 1; // including 1 byte for serverId
		int crc = ModbusPdu.calcCRC16(buffer, 0, size);
		buffer[size] = ModbusPdu.lowByte(crc);
		buffer[size + 1] = ModbusPdu.highByte(crc);
		size = size + 2;
		if (log.isTraceEnabled())
			log.trace("Write: " + ModbusPdu.toHex(buffer, 0, size));
		sendData(size);
	}

	protected void logData(String kind, int start, int length) {
		if (log.isTraceEnabled()) 
			log.trace("Read ({}): {}", kind, ModbusPdu.toHex(buffer, start, length));
	}
	
	protected boolean crcValid(int size) {
		int crc = ModbusPdu.calcCRC16(buffer, 0, size);
		int crc2 = ModbusPdu.bytesToInt16(buffer[size], buffer[size + 1], true); 
		if (crc == crc2)
			return true;
		else {
			if (log.isWarnEnabled())
				log.warn("CRC error (calc: {}, in response: {})", Integer.toHexString(crc), Integer.toHexString(crc2));
			return false;
		}
	}
	
	@Override
	public int waitResponse(ModbusClient modbusClient) throws Exception  {
		if (!openPort())
			return ModbusClient.RESULT_TIMEOUT;
		try {
			expectedBytes = modbusClient.getExpectedPduSize() + 3; // id(1), PDU(n), crc(2)

			// read id
			if (!readToBuffer(0, 1, modbusClient))
				return ModbusClient.RESULT_TIMEOUT;
			if (buffer[0] != modbusClient.getServerId()) {
				logData("bad id", 0, 1);
				log.warn("waitResponse(): Invalid id: {} (expected: {})", buffer[0], modbusClient.getServerId());
				return ModbusClient.RESULT_BAD_RESPONSE;
			}

			// read function (bit7 means exception)
			if (!readToBuffer(1, 1, modbusClient))
				return ModbusClient.RESULT_TIMEOUT;
			if ((buffer[1] & 0x7f) != modbusClient.getFunction()) {
				logData("bad function", 0, 2);
				log.warn("waitResponse(): Invalid function: {} (expected: {})", buffer[1], modbusClient.getFunction());
				return ModbusClient.RESULT_BAD_RESPONSE;
			}

			if ((buffer[1] & 0x80) != 0) {
				// EXCEPTION
				expectedBytes = 5; // id(1), function(1), exception code(1), crc(2)
				if (!readToBuffer(2, 3, modbusClient)) // exception code + CRC
					return ModbusClient.RESULT_TIMEOUT;
				if (crcValid(3)) {
					logData("exception", 0, expectedBytes);
					modbusClient.setPduSize(2); // function + exception code
					modbusClient.writeToPdu(buffer, 1, modbusClient.getPduSize(), 0);
					return ModbusClient.RESULT_EXCEPTION;
				}
				else {
					logData("bad crc (exception)", 0, expectedBytes);
					return ModbusClient.RESULT_BAD_RESPONSE;
				}
			}
			else {
				// NORMAL RESPONSE
				if (!readToBuffer(2, modbusClient.getExpectedPduSize() + 1, modbusClient)) // data + CRC (without function)
					return ModbusClient.RESULT_TIMEOUT;
				// CRC check of (serverId + PDU)
				if (crcValid(1 + modbusClient.getExpectedPduSize())) {
					logData("normal", 0, expectedBytes);
					modbusClient.setPduSize(modbusClient.getExpectedPduSize());
					modbusClient.writeToPdu(buffer, 1, modbusClient.getPduSize(), 0);
					return ModbusClient.RESULT_OK;
				}
				else {
					logData("bad crc", 0, expectedBytes);
					return ModbusClient.RESULT_BAD_RESPONSE;
				}
			}
		} finally {
			if (!keepConnection)
				close();
		}
	}
	
}
