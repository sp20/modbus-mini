package ua.com.certa.modbus.server;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModbusTcpServer extends AModbusServer implements Runnable {

	public static final int MAX_CONNECTIONS = 10;
	
	private final String localAddressString; // null means any
	private final int localPort; // zero means default (502)
	private final int clientTimeout; // must be > 0

	private AtomicBoolean active = new AtomicBoolean(false);
	private Thread thread;
	private List<ModbusTcpServerConnection> connections = new  ArrayList<ModbusTcpServerConnection>(MAX_CONNECTIONS); 
	private volatile ServerSocket serverSocket;
	
	public ModbusTcpServer(String localIP, int localPort, int clientTimeout, 
			int inputsStart, int inputsCount, int coilsStart, int coilsCount,
			int iregsStart, int iregsCount, int hregsStart, int hregsCount) 
	{
		super(inputsStart, inputsCount, coilsStart, coilsCount, iregsStart, iregsCount, hregsStart, hregsCount);
		this.localAddressString = (localIP != null) ? localIP : "0.0.0.0";
		this.localPort = localPort;
		this.clientTimeout = clientTimeout;
	}

	@Override
	public void Start() {
		if (!active.getAndSet(true)) {
			log.info("Starting server");
			thread = new Thread(null, this);
			thread.setDaemon(true);
			thread.start();
		}
	}

	@Override
	public void Stop() {
		if (active.getAndSet(false)) {
			log.info("Stopping server");
			thread.interrupt();
			closeSocket();
			try {
				thread.join();
			} catch (InterruptedException e) {
				// Restore the interrupted status (see javadocs)
	             Thread.currentThread().interrupt();
			}
			thread = null; // we must create new thread to restart
		}
	}

	private void closeSocket() {
		try {
			if (serverSocket != null)
				serverSocket.close();
		} catch (Exception e) {
			log.error("EXCEPTION in serverSocket.close()", e);
		}
	}
	
	@Override
	public void run() {
		log.debug("SERVER THREAD START");
		try {
			// main cycle
			while (active.get() && !Thread.currentThread().isInterrupted()) {
				try {
					serverSocket = new ServerSocket(localPort, 0, InetAddress.getByName(localAddressString));
					try {
						while (active.get() && !Thread.currentThread().isInterrupted()) {
							// internal cycle to prevent frequent creation of serverSocket
							try {
								acceptConnection(serverSocket.accept());
							} catch (SocketTimeoutException e) {
								// in case of timeout continue loop (will never come here because we didn't set timeout for this socket)
							}
						}
					} finally {
						closeSocket();
					}
				} catch (SocketException e) {
					if (!active.get())
						break; // socket was closed while stopping server
					else {
						log.error("SocketException in main cycle: {}", e.getMessage());
						Thread.sleep(5000);
					}
				} catch (Exception e) {
					if (e instanceof InterruptedException)
						break; // server must be stopped 
					else {
						log.error("EXCEPTION in main cycle", e);
						Thread.sleep(5000);
					}
				}
			}
		} catch (Throwable t) {
			if (!(t instanceof InterruptedException))
				log.error("SERVER THREAD TERMINATED ", t);
		}
		active.set(false);
		closeConnections();
		log.debug("SERVER THREAD END");
	}

	private void acceptConnection(Socket socket) throws SocketException {
		socket.setSoTimeout(clientTimeout);
		synchronized(connections) {
			if (connections.size() < MAX_CONNECTIONS)
				connections.add(new ModbusTcpServerConnection(this, socket));
			else
				log.warn("Too many connections");
		}
	}

	protected void unregisterConnection(ModbusTcpServerConnection conn) {
		synchronized(connections) {
			connections.remove(conn);
		}
	}
	
	private void closeConnections() {
		synchronized(connections) {
			for (ModbusTcpServerConnection c : connections)
				c.close();
		}
	}

	@Override
	protected Logger getLog() {
		return LoggerFactory.getLogger(ModbusTcpServer.class);
	}
	
}

class ModbusTcpServerConnection extends AModbusServerConnection implements Runnable, Closeable {
	
	private AtomicBoolean active = new AtomicBoolean(true);
	private final Thread thread;
	private final Socket socket;

	private final byte[] buffer = new byte[MAX_PDU_SIZE + 7]; // Modbus TCP/IP ADU: [MBAP(7), PDU(n)]

	public ModbusTcpServerConnection(AModbusServer server, Socket socket) 
	{
		super(server);
		this.socket = socket;
		thread = new Thread(null, this);
		thread.setDaemon(true);
		thread.start();
	}
	
	@Override
	public void close() {
		if (active.getAndSet(false)) {
			server.log.debug("Closing connection");
			thread.interrupt();
			closeSocket();
			try {
				thread.join();
			} catch (InterruptedException e) {
				// Restore the interrupted status (see javadocs)
	             Thread.currentThread().interrupt();
			}
		}
	}

	private void closeSocket() {
		try {
			socket.close();
		} catch (IOException e) {
			server.log.error("EXCEPTION in ModbusTcpServerConnection.close()", e);
		}
	}
	
	private boolean waitRequest() throws IOException {
		InputStream in = socket.getInputStream();
		// read first 3 parameters (6 bytes) of MBAP header (transaction id, protocol id, length)
		int res = in.read(buffer, 0, 6);
		if (res < 0) {
			server.log.trace("End of Stream");
			close();
			return false;
		}
		if (res < 6) {
			logData("Read (incomplete MBAP): ", buffer, 0, res);
			server.log.warn("Error reading MBAP header");
			close();
			return false;
		}
		// get length of remaining data (server id and PDU)
		int len = toUnsigned16(bytesToInt16(buffer[5], buffer[4]));
		if ((len < 2) || (len > (buffer.length - 6))) {
			logData("Read (invalid length): ", buffer, 0, res);
			server.log.warn("Invalid data length: {}", len);
			close();
			return false;
		}
		// read data
		res = in.read(buffer, 6, len);
		if (res < len) {
			logData("Read (incomplete PDU): ", buffer, 0, res + 6);
			server.log.warn("Error reading PDU");
			close();
			return false;
		}
		logData("Read (complete): ", buffer, 0, len + 6);
		pduSize = len - 1;
		System.arraycopy(buffer, 7, pdu, 0, pduSize);
		return true;
	}
	
	private void sendResponse() throws IOException {
		if (pduSize == 0)
			return;
		buffer[4] = highByte(pduSize + 1);
		buffer[5] = lowByte(pduSize + 1); 
		System.arraycopy(pdu, 0, buffer, 7, pduSize);
		int size = pduSize + 7;
		logData("Write: ", buffer, 0, size);
		socket.getOutputStream().write(buffer, 0, size);
	}

	@Override
	public void run() {
		server.log.debug("CONNECTION THREAD START");
		server.log.info("Client connected: {}", socket.getRemoteSocketAddress());
		try {
			try {
				// main cycle
				while (active.get() && !Thread.currentThread().isInterrupted()) {
					if (waitRequest()) {
						if (processRequest())
							sendResponse();
					} else
						Thread.sleep(1000);
				}
			} finally {
				active.set(false);
				closeSocket();
			} 
		} catch (SocketTimeoutException toe) {
			// Client was idle too long. Disconnect it
			server.log.debug("Client timeout");
		} catch (InterruptedException ie) {
			// This is normal situation. Just stopping the thread
		} catch (Throwable t) {
			server.log.error("CONNECTION THREAD TERMINATED ", t);
		}
		((ModbusTcpServer)server).unregisterConnection(this);
		server.log.debug("CONNECTION THREAD END");
		server.log.info("Client disconnected: {}", socket.getRemoteSocketAddress());
	}
	
}
