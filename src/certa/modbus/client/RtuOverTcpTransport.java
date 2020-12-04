package certa.modbus.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

import org.slf4j.LoggerFactory;

import certa.modbus.ModbusPdu;

public class RtuOverTcpTransport extends AbstractRtuTransport {

	private final String remoteAddressString;
	private final int remotePort; 
	private final String localAddressString; // null means any
	private final int localPort; // zero means any
	private final int connectTimeout;
	private Socket socket;

	public RtuOverTcpTransport(String remoteHost, int remotePort, String localIP, int localPort, 
			int connectTimeout, int responseTimeout, int pause, boolean keepConnection) {
		super(responseTimeout, pause, keepConnection, LoggerFactory.getLogger(RtuOverTcpTransport.class));
		this.remoteAddressString = remoteHost;
		this.remotePort = remotePort;
		this.localAddressString = (localIP != null) ? localIP : "0.0.0.0";
		this.localPort = localPort;
		this.connectTimeout = connectTimeout;
	}

	// this method must be synchronized with close()
	@Override
	synchronized protected boolean openPort() throws IOException {
		if ((socket == null) || socket.isClosed()) {
			log.info("Opening socket: {}:{} <-> {}:{}, respTO: {}, connTO: {}, pause: {}", 
					localAddressString, localPort, remoteAddressString, remotePort, timeout, connectTimeout, pause);
			InetSocketAddress localAddress = (localAddressString == null || localAddressString.isEmpty()) ? 
					null : new InetSocketAddress(InetAddress.getByName(localAddressString), localPort);
			InetSocketAddress remoteAddress = new InetSocketAddress(InetAddress.getByName(remoteAddressString), remotePort);

			// Sometimes there is a bug when we connect shortly after close. So let's make a delay before connection
			try {
				Thread.sleep(500);
			} catch (InterruptedException e1) {
				log.error("Error opening socket {} (sleep: {}", remoteAddress, e1);
			}

			socket = new Socket();
			try {
				socket.setSoLinger(true, 0); // force always close the socket abortive with RST message
				socket.bind(localAddress);
				socket.connect(remoteAddress, connectTimeout);
				socket.setSoTimeout(timeout);
			} catch (IOException e) {
				close();
				throw e;
			}
			log.info("Socket opened: {} <-> {}", socket.getLocalSocketAddress(), socket.getRemoteSocketAddress());
		}
		return true;
	}

	// this method may be called from other thread
	@Override
	synchronized public void close() {
		if ((socket != null) && !socket.isClosed()) {
			log.info("Closing socket");
			try {
				socket.close();
			} catch (IOException e) {
				log.error("Error closing socket {}: {}", socket.getRemoteSocketAddress(), e);
			}
			log.info("Socket closed");
		}
	}

	@Override
	protected void clearInput() throws IOException {
		InputStream in = socket.getInputStream();
		int avail = in.available();
		while (avail > 0) {
			int count = Math.min(avail, buffer.length);
			in.read(buffer, 0, count);
			if (log.isWarnEnabled())
				log.warn("Unexpected input: " + ModbusPdu.toHex(buffer, 0, count));
			avail = in.available();
		}
	}

	@Override
	protected void sendData(int size) throws IOException {
		socket.getOutputStream().write(buffer, 0, size);
	}

	@Override
	protected boolean readToBuffer(int start, int length, ModbusClient modbusClient) throws IOException {
		InputStream in = socket.getInputStream();
		long now = System.currentTimeMillis();
		long deadline = now + timeout;
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
			if (res < 0) // -1 means End Of File. Socket must be closed and reopened.
				throw new IOException("Socket's InputStream is at End Of File");
			offset += res;
			bytesToRead -= res;
			if (bytesToRead > 0) // only to avoid redundant call of System.currentTimeMillis()
				now = System.currentTimeMillis();
		}
		res = length - bytesToRead; // total bytes read
		if (res < length) {
			if ((res > 0) && log.isTraceEnabled())
				log.trace("Read (incomplete): " + ModbusPdu.toHex(buffer, 0, start + res));
			log.warn("Response timeout ({} bytes, need {})", start + res, expectedBytes);
			return false;
		}
		else
			return true;
	}

}
