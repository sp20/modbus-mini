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

public class ModbusRtuOverUdpClient extends AModbusClient {
	private static final Logger log = LoggerFactory.getLogger(ModbusRtuOverUdpClient.class);

	private final String remoteAddressString;
	private final int remotePort; 
	private final String localAddressString; // null means any
	private final int localPort; // zero means any
	private final int timeout;

	private DatagramSocket socket;
	private DatagramPacket inPacket;
	private DatagramPacket outPacket;

	private final byte[] buffer = new byte[MAX_PDU_SIZE + 7]; // Modbus RTU ADU: [ID(1), PDU(n), CRC(2)]

	public ModbusRtuOverUdpClient(String remoteHost, int remotePort, String localIP, int localPort, int timeout) {
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
		log.info("Socket opened: {} <-> {}", socket.getLocalSocketAddress(), outPacket.getSocketAddress());
	}

	@Override
	protected Logger getLog() {
		return log;
	}
	
	@Override
	protected void sendRequest() throws IOException {
		openSocket();
		buffer[0] = getServerId();
		System.arraycopy(pdu, 0, buffer, 1, pduSize);
		int size = pduSize + 1;
		int crc = ModbusUtils.calcCRC16(buffer, 0, size);
		buffer[size] = lowByte(crc);
		buffer[size + 1] = highByte(crc);
		size = size + 2;
		if (log.isTraceEnabled())
			log.trace("Write: " + ModbusUtils.toHex(buffer, 0, size));
		socket.send(outPacket);
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
					(buffer[0] == getServerId()))
			{
				if (log.isTraceEnabled())
					log.trace("Read: " + ModbusUtils.toHex(buffer, 0, inPacket.getLength()));
				return true;
			}
			else {
				if (log.isWarnEnabled())
					log.warn("Unexpected input: " + ModbusUtils.toHex(buffer, 0, inPacket.getLength()));
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
		// Minimal response is [ID(1), function(1), exception code(1), CRC(2)]
		if (inPacket.getLength() < 5) {
			log.warn("Invalid size: {} (expected: >= 5)", inPacket.getLength());
			return RESULT_BAD_RESPONSE;
		}
		if ((buffer[1] & 0x7f) != getFunction()) {
			log.warn("waitResponse(): Invalid function: {} (expected: {})", buffer[1], getFunction());
			return RESULT_BAD_RESPONSE;
		}
		if ((buffer[1] & 0x80) != 0) {
			// EXCEPTION
			// size already checked
			pduSize = 2; // function + exception code
			System.arraycopy(buffer, 1, pdu, 0, pduSize);
			return RESULT_EXCEPTION;
		}
		else {
			// NORMAL RESPONSE
			pduSize = getExpectedPduSize();
			// Check size
			if (inPacket.getLength() < 3 + pduSize) {
				log.warn("Invalid size: {} (expected: {})", inPacket.getLength(), 3 + pduSize);
				return RESULT_BAD_RESPONSE;
			}
			// CRC check of (serverId + PDU)
			if (!crcValid(1 + pduSize)) {
				log.warn("Invalid CRC");
				return RESULT_BAD_RESPONSE;
			}
			System.arraycopy(buffer, 1, pdu, 0, pduSize);
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
