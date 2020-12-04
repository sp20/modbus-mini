package certa.modbus.client;

import java.util.Arrays;

import jssc.SerialPort;
import jssc.SerialPortException;

import org.slf4j.LoggerFactory;

import certa.modbus.ModbusPdu;

public class RtuTransportJssc extends AbstractRtuTransport {

	private final int baudRate;
	private final int dataBits;
	private final int parity;
	private final int stopBits;
	private final SerialPort port;

	public static final int PARITY_NONE = SerialPort.PARITY_NONE;
    public static final int PARITY_ODD  = SerialPort.PARITY_ODD;
    public static final int PARITY_EVEN = SerialPort.PARITY_EVEN;
    
	public RtuTransportJssc(String portName, int baudRate, int dataBits, int parity, int stopBits, int timeout, int pause) {
		super(timeout, pause, true, LoggerFactory.getLogger(RtuTransportJssc.class));
		this.port = new SerialPort(portName);
		this.baudRate = baudRate;
		this.dataBits = dataBits;
		this.parity = parity;
		this.stopBits = stopBits;
	}

	public static String parityStr(int code) {
		return code == 0 ? "N" : (code == 1 ? "O" : (code == 2 ? "E" : "?"));
	}
	
	// this method must be synchronized with close()
	@Override
	synchronized protected boolean openPort() throws SerialPortException, InterruptedException {
		if (Thread.currentThread().isInterrupted())
			throw new InterruptedException();
		if (!port.isOpened()) {
			log.info("Opening port: {}, {}, {}-{}-{}", port.getPortName(), baudRate, dataBits, parityStr(parity), stopBits);
			try {
				port.openPort();
				port.setParams(baudRate, dataBits, stopBits, parity);
			} catch (SerialPortException e) {
				close();
				throw e;
			}
			log.info("Port opened: {}", port.getPortName());
		}
		return true;
	}

	// this method may be called from other thread
	@Override
	synchronized public void close() {
		if (port.isOpened()) {
			log.info("Closing port: {}", port.getPortName());
			try {
				port.closePort();
			} catch (SerialPortException e) {
				log.error("Error closing port {}: {}", port.getPortName(), e);
			}
			log.info("Port {} closed", port.getPortName());
		}
	}

	@Override
	protected void clearInput() throws SerialPortException {
		byte[] buf = port.readBytes();
		while (buf != null) {
			if (log.isWarnEnabled())
				log.warn("Unexpected input: " + ModbusPdu.toHex(buf, 0, buf.length));
			buf = port.readBytes();
		}
	}

	@Override
	protected void sendData(int size) throws SerialPortException {
		port.writeBytes(Arrays.copyOfRange(buffer, 0, size));
	}

	@Override
	protected boolean readToBuffer(int start, int length, ModbusClient modbusClient) throws SerialPortException, InterruptedException {
		long now = System.currentTimeMillis();
		long deadline = now + timeout;
		int offset = start;
		int bytesToRead = length;
		boolean lastTry = false;
		while (bytesToRead > 0) {
			int avail = Math.min(port.getInputBufferBytesCount(), bytesToRead);
			if (avail > 0) {
				byte[] buf = port.readBytes(avail);
				avail = buf.length;
				System.arraycopy(buf, 0, buffer, offset, avail);
				offset += avail;
				bytesToRead -= avail;
			}
			if (bytesToRead > 0) {
				Thread.sleep(20);
				now = System.currentTimeMillis();
				// After reaching deadline we need to make last attempt to read data.
				// It is possible that something present in input buffer
				// (happens on heavy loaded virtual server)
				if (now > deadline) {
					if (lastTry)
						break;
					else
						lastTry = true;
				}
			}
		}
		int res = length - bytesToRead; // total bytes read
		if (res < length) {
			if ((res > 0) && log.isTraceEnabled())
				log.trace("Read (incomplete): " + ModbusPdu.toHex(buffer, 0, start + res));
			log.warn("Response from {} timeout ({} bytes, need {})", modbusClient.getServerId(), start + res, expectedBytes);
			return false;
		}
		else
			return true;
	}

}
