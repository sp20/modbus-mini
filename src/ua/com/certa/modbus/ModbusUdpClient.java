package ua.com.certa.modbus;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModbusUdpClient extends AModbusClient {
	private static final Logger log = LoggerFactory.getLogger(ModbusUdpClient.class);

	private final String remoteAddressString;
	private final int remotePort; // zero means default (502) 
	private final String localAddressString; // null means any
	private final int localPort; // zero means any
	private final int timeout;

	private DatagramSocket socket;
	private DatagramPacket inPacket;
	private DatagramPacket outPacket;
	private int transactionId = 0;

	private final byte[] buffer = new byte[MAX_PDU_SIZE + 7]; // Modbus TCP/IP ADU: [MBAP(7), PDU(n)]

	public ModbusUdpClient(String remoteHost, int remotePort, String localIP, int localPort, int timeout) {
		this.remoteAddressString = remoteHost;
		this.remotePort = (remotePort == 0) ? 502 : remotePort;
		this.localAddressString = (localIP != null) ? localIP : "0.0.0.0";
		this.localPort = localPort;
		this.timeout = timeout;
	}

	private void openSocket() throws SocketException, UnknownHostException {
		if (socket != null)
			return;
		log.info("Opening socket: {}:{} <-> {}:{}", localAddressString, localPort, remoteAddressString, remotePort);
		InetSocketAddress remoteAddress = new InetSocketAddress(remoteAddressString, remotePort);
		InetSocketAddress localAddress = new InetSocketAddress(localAddressString, localPort);
		socket = new DatagramSocket(localAddress);
		socket.setSoTimeout(timeout);
		inPacket = new DatagramPacket(buffer, buffer.length);
		outPacket = new DatagramPacket(buffer, buffer.length, remoteAddress);
		log.info("Socket opened: {} <-> {}", localAddress, remoteAddress);
	}

	@Override
	protected void sendRequest() throws IOException {
		openSocket();
		transactionId++;
		if (transactionId > 65535)
			transactionId = 1;
		buffer[0] = highByte(transactionId);
		buffer[1] = lowByte(transactionId);
		buffer[2] = 0; // Protocol identifier (0x0006)
		buffer[3] = 6; 
		buffer[4] = highByte(pduSize + 1);
		buffer[5] = lowByte(pduSize + 1); 
		buffer[6] = getServerId(); 
		System.arraycopy(pdu, 0, buffer, 7, pduSize);
		int size = pduSize + 7;
		if (log.isTraceEnabled())
			log.trace("Write: " + ModbusUtils.toHex(buffer, 0, size));
		socket.send(outPacket);
	}

	boolean waitCorrectPacket() throws IOException {
		long now = System.currentTimeMillis();
		long deadline = now + timeout;
		while (now < deadline) {
			try {
				socket.receive(inPacket);
			} catch (SocketTimeoutException te) {
				log.warn("socket.receive() timeout");
				return false;
			}
			if (inPacket.getSocketAddress().equals(outPacket.getSocketAddress()) &&
					(buffer[0] == highByte(transactionId)) &&
					(buffer[1] == lowByte(transactionId)) &&
					(buffer[6] == getServerId()))
			{
				if (log.isTraceEnabled())
					log.trace("Read: " + ModbusUtils.toHex(buffer, 0, inPacket.getLength()));
				return true;
			}
			else {
				if (log.isTraceEnabled())
					log.trace("Dropped: " + ModbusUtils.toHex(buffer, 0, inPacket.getLength()));
				now = System.currentTimeMillis();
			}
		}
		log.warn("Response timeout");
		return false;
	}

	@Override
	protected int waitResponse() throws IOException {
		openSocket();
		if (!waitCorrectPacket())
			return RESULT_TIMEOUT;
		// Minimal response is [MBAP(7), function(1), exception code(1)]
		if (inPacket.getLength() < 9) {
			log.warn("Invalid size: {} (expected: >= 9)", inPacket.getLength());
			return RESULT_BAD_RESPONSE;
		}
		if ((buffer[7] & 0x7f) != getFunction()) {
			log.warn("waitResponse(): Invalid function: {} (expected: {})", buffer[1], getFunction());
			return RESULT_BAD_RESPONSE;
		}
		if ((buffer[7] & 0x80) != 0) {
			// EXCEPTION
			// size already checked
			pduSize = 2; // function + exception code
			System.arraycopy(buffer, 1, pdu, 0, pduSize);
			return RESULT_EXCEPTION;
		}
		else {
			// NORMAL RESPONSE
			pduSize = getExpectedPduSize();
			if (inPacket.getLength() < 7 + pduSize) {
				log.warn("Invalid size: {} (expected: {})", inPacket.getLength(), 7 + pduSize);
				return RESULT_BAD_RESPONSE;
			}
			System.arraycopy(buffer, 7, pdu, 0, pduSize);
			return RESULT_OK;
		}
	}

	@Override
	public void close() {
		DatagramSocket t = socket;
		socket = null;
		if (t != null) {
			log.info("Closing socket");
			t.close();
			log.info("Socket closed");
		}
	}

}
