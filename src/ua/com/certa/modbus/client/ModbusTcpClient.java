package ua.com.certa.modbus.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ua.com.certa.modbus.ModbusUtils;

public class ModbusTcpClient extends AModbusClient {
	private static final Logger log = LoggerFactory.getLogger(ModbusTcpClient.class);

	private final String remoteAddressString;
	private final int remotePort; // zero means default (502) 
	private final String localAddressString; // null means any
	private final int localPort; // zero means any
	private final int connectTimeout;
	private final int responseTimeout;
	private final int pause;
	private final boolean keepConnection; 

	private Socket socket;
	private int transactionId = 0;
	private int expectedBytes; // for logging

	private final byte[] buffer = new byte[MAX_PDU_SIZE + 7]; // Modbus TCP/IP ADU: [MBAP(7), PDU(n)]

	public ModbusTcpClient(String remoteHost, int remotePort, String localIP, int localPort, int connectTimeout, int responseTimeout, int pause, boolean keepConnection) {
		this.remoteAddressString = remoteHost;
		this.remotePort = (remotePort == 0) ? 502 : remotePort;
		this.localAddressString = (localIP != null) ? localIP : "0.0.0.0";
		this.localPort = localPort;
		this.connectTimeout = connectTimeout;
		this.responseTimeout = responseTimeout;
		this.keepConnection = keepConnection;
		this.pause = pause;
	}

	private void openSocket() throws IOException {
		if (socket != null)
			return;
		log.info("Opening socket: {}:{} <-> {}:{}", localAddressString, localPort, remoteAddressString, remotePort);
		InetSocketAddress localAddress = new InetSocketAddress(InetAddress.getByName(localAddressString), localPort);
		InetSocketAddress remoteAddress = new InetSocketAddress(InetAddress.getByName(remoteAddressString), remotePort);
		socket = new Socket();
		try {
			socket.bind(localAddress);
			socket.connect(remoteAddress, connectTimeout);
			socket.setSoTimeout(responseTimeout);
		} catch (IOException e) {
			close();
			throw e;
		}
		log.info("Socket opened: {} <-> {}", socket.getLocalSocketAddress(), socket.getRemoteSocketAddress());
	}

	public void clearInput() throws IOException {
		InputStream in = socket.getInputStream();
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
	protected void sendRequest() throws IOException, InterruptedException {
		if (pause > 0)
			Thread.sleep(pause);
		openSocket();
		clearInput();
		transactionId++;
		if (transactionId > 65535)
			transactionId = 1;
		buffer[0] = highByte(transactionId);
		buffer[1] = lowByte(transactionId);
		buffer[2] = 0; // Protocol identifier (0)
		buffer[3] = 0; //
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
		long deadline = now + responseTimeout;
		int offset = start;
		int bytesToRead = length;
		int res;
		while ((now < deadline) && (bytesToRead > 0)) {
			try {
				res = in.read(buffer, offset, bytesToRead);
			} catch (SocketTimeoutException e) {
				res = 0;
				log.debug("readToBuffer(): SocketTimeoutException");
				// do not break, because SocketTimeoutException may appear before deadline
			}
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

	private void logData(String kind, int start, int length) {
		if (log.isTraceEnabled()) 
			log.trace("Read ({}): {}", kind, ModbusUtils.toHex(buffer, start, length));
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
				logData("bad transaction", 0, 8);
				log.warn("waitResponse(): Invalid transaction id: {} (expected: {})", tid, transactionId);
				return RESULT_BAD_RESPONSE;
			}
			// check server id
			if (buffer[6] != getServerId()) {
				logData("bad id", 0, 8);
				log.warn("waitResponse(): Invalid server id: {} (expected: {})", buffer[6], getServerId());
				return RESULT_BAD_RESPONSE;
			}
			// check function (bit7 means exception)
			if ((buffer[7] & 0x7f) != getFunction()) {
				logData("bad function", 0, 8);
				log.warn("waitResponse(): Invalid function: {} (expected: {})", buffer[7], getFunction());
				return RESULT_BAD_RESPONSE;
			}
			// read rest of response
			if ((buffer[7] & 0x80) != 0) {
				// EXCEPTION
				expectedBytes = 9; // MBAP(7), function(1), exception code(1)
				if (!readToBuffer(8, 1)) // exception code
					return RESULT_TIMEOUT;
				logData("exception", 0, expectedBytes);
				pduSize = 2; // function + exception code
				System.arraycopy(buffer, 7, pdu, 0, pduSize);
				return RESULT_EXCEPTION;
			}
			else {
				// NORMAL RESPONSE
				if (!readToBuffer(8, getExpectedPduSize() - 1)) // data (without function)
					return RESULT_TIMEOUT;
				logData("normal", 0, expectedBytes);
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
