package certa.modbus.client;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import org.slf4j.LoggerFactory;

import certa.modbus.ModbusPdu;

public class TcpOverUdpTransport extends AbstractTcpTransport {

	private final String remoteAddressString;
	private final int remotePort; // zero means default (502) 
	private final String localAddressString; // null means any
	private final int localPort; // zero means any
	private DatagramSocket socket;
	private DatagramPacket inPacket;
	private DatagramPacket outPacket;

	public TcpOverUdpTransport(String remoteHost, int remotePort, String localIP, int localPort, int timeout, int pause) {
		super(timeout, pause, false, LoggerFactory.getLogger(TcpOverUdpTransport.class));
		this.remoteAddressString = remoteHost;
		this.remotePort = (remotePort == 0) ? 502 : remotePort;
		this.localAddressString = (localIP != null) ? localIP : "0.0.0.0";
		this.localPort = localPort;
	}

	// this method must be synchronized with close()
	@Override
	synchronized protected void openPort() throws SocketException, UnknownHostException {
		if ((socket == null) || socket.isClosed()) {
			log.info("Opening socket: {}:{} <-> {}:{}", localAddressString, localPort, remoteAddressString, remotePort);
			InetSocketAddress remoteAddress = new InetSocketAddress(remoteAddressString, remotePort);
			InetSocketAddress localAddress = (localAddressString == null || localAddressString.isEmpty()) ? 
					null : new InetSocketAddress(InetAddress.getByName(localAddressString), localPort);
			socket = new DatagramSocket(localAddress);
			socket.setSoTimeout(timeout);
			inPacket = new DatagramPacket(buffer, buffer.length);
			outPacket = new DatagramPacket(buffer, buffer.length, remoteAddress);
			log.info("Socket opened: {} <-> {}", socket.getLocalSocketAddress(), outPacket.getSocketAddress());
		}
	}

	// this method may be called from other thread
	@Override
	synchronized public void close() {
		if ((socket != null) && !socket.isClosed()) {
			log.info("Closing socket");
			socket.close();
			log.info("Socket closed");
		}
	}

	@Override
	protected void clearInput() throws IOException {
		// do nothing
	}

	@Override
	protected void sendData(int size) throws IOException {
		socket.send(outPacket);
	}

	private boolean waitCorrectPacket(ModbusClient modbusClient) throws IOException {
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
					(buffer[0] == ModbusPdu.highByte(transactionId)) &&
					(buffer[1] == ModbusPdu.lowByte(transactionId)) &&
					(buffer[6] == modbusClient.getServerId()))
			{
				if (log.isTraceEnabled())
					log.trace("Read: " + ModbusPdu.toHex(buffer, 0, inPacket.getLength()));
				return true;
			}
			else {
				if (log.isWarnEnabled())
					log.warn("Unexpected input: " + ModbusPdu.toHex(buffer, 0, inPacket.getLength()));
				now = System.currentTimeMillis();
			}
		}
		log.warn("Response timeout");
		return false;
	}

	@Override
	public int waitResponse(ModbusClient modbusClient) throws IOException {
		openPort();
		if (!waitCorrectPacket(modbusClient))
			return ModbusClient.RESULT_TIMEOUT;
		// Minimal response is [MBAP(7), function(1), exception code(1)]
		if (inPacket.getLength() < 9) {
			log.warn("Invalid size: {} (expected: >= 9)", inPacket.getLength());
			return ModbusClient.RESULT_BAD_RESPONSE;
		}
		if ((buffer[7] & 0x7f) != modbusClient.getFunction()) {
			log.warn("waitResponse(): Invalid function: {} (expected: {})", buffer[1], modbusClient.getFunction());
			return ModbusClient.RESULT_BAD_RESPONSE;
		}
		if ((buffer[7] & 0x80) != 0) {
			// EXCEPTION
			// size already checked
			modbusClient.setPduSize(2); // function + exception code
			modbusClient.writeToPdu(buffer, 7, 2, 0);
			return ModbusClient.RESULT_EXCEPTION;
		}
		else {
			// NORMAL RESPONSE
			int size = modbusClient.getExpectedPduSize();
			if (inPacket.getLength() < 7 + size) {
				log.warn("Invalid size: {} (expected: {})", inPacket.getLength(), 7 + size);
				return ModbusClient.RESULT_BAD_RESPONSE;
			}
			modbusClient.setPduSize(size);
			modbusClient.writeToPdu(buffer, 7, size, 0);
			return ModbusClient.RESULT_OK;
		}
	}

	@Override
	protected boolean readToBuffer(int start, int length, ModbusClient modbusClient) throws Exception {
		// Not used because waitResponse is override
		return false;
	}

}
