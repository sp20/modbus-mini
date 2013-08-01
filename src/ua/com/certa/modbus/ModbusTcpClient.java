package ua.com.certa.modbus;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModbusTcpClient extends AModbusClient {
	private static final Logger log = LoggerFactory.getLogger(ModbusTcpClient.class);

	private final String remoteAddressString;
	private final int remotePort; // zero means default (502) 
	private final String localAddressString; // null means any
	private final int localPort; // zero means any
	private final int timeout;
	private final boolean keepConnection; 

	private Socket socket;
	private int transactionId = 0;
	private int expectedBytes; // for logging

	private final byte[] buffer = new byte[MAX_PDU_SIZE + 7]; // Modbus TCP/IP ADU: [MBAP(7), PDU(n)]

	public ModbusTcpClient(String remoteHost, int remotePort, String localIP, int localPort, int timeout, boolean keepConnection) {
		this.remoteAddressString = remoteHost;
		this.remotePort = (remotePort == 0) ? 502 : remotePort;
		this.localAddressString = (localIP != null) ? localIP : "0.0.0.0";
		this.localPort = localPort;
		this.timeout = timeout;
		this.keepConnection = keepConnection;
	}

	private void openSocket() throws IOException {
		if (socket != null)
			return;
		log.info("Opening socket: {}:{} <-> {}:{}", localAddressString, localPort, remoteAddressString, remotePort);
		InetAddress remoteAddress = InetAddress.getByName(remoteAddressString);
		InetAddress localAddress = InetAddress.getByName(localAddressString);
		socket = new Socket(remoteAddress, remotePort, localAddress, localPort);
		socket.setSoTimeout(timeout);
		log.info("Socket opened: {} <-> {}", socket.getLocalSocketAddress(), socket.getRemoteSocketAddress());
	}

	public void clearInput() throws IOException {
		InputStream in = socket.getInputStream();
		int avail = in.available();
		while (avail > 0) {
			int count = Math.min(avail, buffer.length);
			in.read(buffer, 0, count);
			if (log.isTraceEnabled())
				log.trace("Clear input: " + ModbusUtils.toHex(buffer, 0, count));
			avail = in.available();
		}
	}

	@Override
	protected void sendRequest() throws IOException {
		openSocket();
		clearInput();
		transactionId++;
		if (transactionId > 65535)
			transactionId = 1;
		buffer[0] = highByte(transactionId);
		buffer[1] = lowByte(transactionId);
		buffer[2] = 0; // Protocol identifier (0x0006)
		buffer[3] = 6; //
		buffer[4] = highByte(pduSize + 1);
		buffer[5] = lowByte(pduSize + 1); 
		buffer[6] = getServerId(); 
		System.arraycopy(pdu, 0, buffer, 7, pduSize);
		int size = pduSize + 7;
		if (log.isTraceEnabled())
			log.trace("Write: " + ModbusUtils.toHex(buffer, 0, size));
		socket.getOutputStream().write(buffer, 0, size);
	}

	private boolean readToBuffer(int start, int length) throws IOException {
		InputStream in = socket.getInputStream();
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
			log.warn("Response timeout ({} bytes, need {})", start + res, expectedBytes);
			return false;
		}
		else
			return true;
	}

	@Override
	protected int waitResponse() throws IOException {
		openSocket();
		try {
			expectedBytes = getExpectedPduSize() + 7; // MBAP(7), PDU(n)
			// read MBAP(7 bytes) + function(1 byte)
			if (!readToBuffer(0, 8))
				return RESULT_TIMEOUT;
			// check transaction id
			int tid = toUnsigned16(bytesToInt16(buffer[1], buffer[0]));
			if (tid != transactionId) {
				log.warn("waitResponse(): Invalid transaction id: {} (expected: {})", tid, transactionId);
				return RESULT_BAD_RESPONSE;
			}
			// check server id
			if (buffer[6] != getServerId()) {
				log.warn("waitResponse(): Invalid server id: {} (expected: {})", buffer[6], getServerId());
				return RESULT_BAD_RESPONSE;
			}
			// check function (bit7 means exception)
			if ((buffer[7] & 0x7f) != getFunction()) {
				log.warn("waitResponse(): Invalid function: {} (expected: {})", buffer[7], getFunction());
				return RESULT_BAD_RESPONSE;
			}
			// read rest of response
			if ((buffer[7] & 0x80) != 0) {
				// EXCEPTION
				expectedBytes = 9; // MBAP(7), function(1), exception code(1)
				if (!readToBuffer(8, 1)) // exception code
					return RESULT_TIMEOUT;
				if (log.isTraceEnabled())
					log.trace("Read (exception): " + ModbusUtils.toHex(buffer, 0, expectedBytes));
				pduSize = 2; // function + exception code
				System.arraycopy(buffer, 7, pdu, 0, pduSize);
				return RESULT_EXCEPTION;
			}
			else {
				// NORMAL RESPONSE
				if (!readToBuffer(8, getExpectedPduSize() - 1)) // data (without function)
					return RESULT_TIMEOUT;
				if (log.isTraceEnabled())
					log.trace("Read (normal): " + ModbusUtils.toHex(buffer, 0, expectedBytes));
				pduSize = getExpectedPduSize();
				System.arraycopy(buffer, 7, pdu, 0, pduSize);
				return RESULT_OK;
			}
		} finally {
			if (!keepConnection)
				close();
		}
	}

	@Override
	public void close() throws IOException {
		Socket t = socket;
		socket = null;
		if (t != null) {
			log.info("Closing socket");
			t.close();
			log.info("Socket closed");
		}
	}

}
