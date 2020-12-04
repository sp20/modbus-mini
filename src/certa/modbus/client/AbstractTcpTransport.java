package certa.modbus.client;

import static certa.modbus.ModbusConstants.*;

import org.slf4j.Logger;

import certa.modbus.ModbusPdu;

public abstract class AbstractTcpTransport implements ModbusClientTransport {
	protected final Logger log;
	protected final int timeout;
	protected final int pause;
	protected final boolean keepConnection; 
	protected int transactionId = 0;
	protected final byte[] buffer = new byte[MAX_PDU_SIZE + 7]; // Modbus TCP/IP ADU: [MBAP(7), PDU(n)]
	protected int expectedBytes; // for logging
	
	public AbstractTcpTransport(int timeout, int pause, boolean keepConnection, Logger log) {
		this.log = log;
		this.timeout = timeout;
		this.pause = pause;
		this.keepConnection = keepConnection;
	}

	protected abstract void openPort() throws Exception;
	protected abstract void clearInput() throws Exception;
	protected abstract void sendData(int size) throws Exception;
	protected abstract boolean readToBuffer(int start, int length, ModbusClient modbusClient) throws Exception;

	@Override
	public void sendRequest(ModbusClient modbusClient) throws Exception {
		if (pause > 0)
			Thread.sleep(pause);
		openPort();
		clearInput();
		transactionId++;
		if (transactionId > 65535)
			transactionId = 1;
		// Fill in MBAP header (7 bytes):
		//  Transaction ID - 2 bytes
		buffer[0] = ModbusPdu.highByte(transactionId);
		buffer[1] = ModbusPdu.lowByte(transactionId);
		//  Protocol identifier (must be 0 for MODBUS) - 2 bytes
		buffer[2] = 0; 
		buffer[3] = 0;
		//  Length (size of PDU + 1 byte for serverId) - 2 bytes
		int size = modbusClient.getPduSize() + 1;
		buffer[4] = ModbusPdu.highByte(size);
		buffer[5] = ModbusPdu.lowByte(size); 
		//  Unit identifier (serverId) - 1 byte
		buffer[6] = modbusClient.getServerId();
		// copy PDU contents
		modbusClient.readFromPdu(0, modbusClient.getPduSize(), buffer, 7);
		size = modbusClient.getPduSize() + 7;
		if (log.isTraceEnabled())
			log.trace("Write: " + ModbusPdu.toHex(buffer, 0, size));
		sendData(size);
	}

	protected void logData(String kind, int start, int length) {
		if (log.isTraceEnabled()) 
			log.trace("Read ({}): {}", kind, ModbusPdu.toHex(buffer, start, length));
	}
	
	@Override
	public int waitResponse(ModbusClient modbusClient) throws Exception  {
		openPort();
		boolean disconnect = !keepConnection;
		try {
			expectedBytes = modbusClient.getExpectedPduSize() + 7; // MBAP(7), PDU(n)
			// read MBAP(7 bytes) + function(1 byte)
			if (!readToBuffer(0, 8, modbusClient))
				return ModbusClient.RESULT_TIMEOUT;
			// check transaction id
			int tid = ModbusPdu.bytesToInt16(buffer[1], buffer[0], true);
			if (tid != transactionId) {
				logData("bad transaction", 0, 8);
				log.warn("waitResponse(): Invalid transaction id: {} (expected: {})", tid, transactionId);
				disconnect = true; // let's reconnect, because we've "lost synchronization" with the server
				return ModbusClient.RESULT_BAD_RESPONSE;
			}
			// check server id
			if (buffer[6] != modbusClient.getServerId()) {
				logData("bad id", 0, 8);
				log.warn("waitResponse(): Invalid server id: {} (expected: {})", buffer[6], modbusClient.getServerId());
				return ModbusClient.RESULT_BAD_RESPONSE;
			}
			// check function (bit7 means exception)
			if ((buffer[7] & 0x7f) != modbusClient.getFunction()) {
				logData("bad function", 0, 8);
				log.warn("waitResponse(): Invalid function: {} (expected: {})", buffer[7], modbusClient.getFunction());
				return ModbusClient.RESULT_BAD_RESPONSE;
			}
			// read rest of response
			if ((buffer[7] & 0x80) != 0) {
				// EXCEPTION
				expectedBytes = 9; // MBAP(7), function(1), exception code(1)
				if (!readToBuffer(8, 1, modbusClient)) // exception code
					return ModbusClient.RESULT_TIMEOUT;
				logData("exception", 0, expectedBytes);
				modbusClient.setPduSize(2); // function + exception code
				modbusClient.writeToPdu(buffer, 7, 2, 0);
				return ModbusClient.RESULT_EXCEPTION;
			}
			else {
				// NORMAL RESPONSE
				int size = modbusClient.getExpectedPduSize();
				if (!readToBuffer(8, size - 1, modbusClient)) // data (without function)
					return ModbusClient.RESULT_TIMEOUT;
				logData("normal", 0, expectedBytes);
				modbusClient.setPduSize(size);
				modbusClient.writeToPdu(buffer, 7, size, 0);
				return ModbusClient.RESULT_OK;
			}
		} finally {
			if (disconnect)
				close();
		}
	}
	
}
