package certa.modbus.client;

import org.slf4j.LoggerFactory;

import com.fazecast.jSerialComm.SerialPort;

import certa.modbus.ModbusPdu;

public class RtuTransportJSerialComm extends AbstractRtuTransport {

	private final int baudRate;
	private final int dataBits;
	private final int parity;
	private final int stopBits;
	private final SerialPort port;

	public static final int PARITY_NONE = SerialPort.NO_PARITY;
    public static final int PARITY_ODD  = SerialPort.ODD_PARITY;
    public static final int PARITY_EVEN = SerialPort.EVEN_PARITY;
    
    public RtuTransportJSerialComm(String portName, int baudRate, int dataBits, int parity, int stopBits, int timeout, int pause) {
		super(timeout, pause, true, LoggerFactory.getLogger(RtuTransportJSerialComm.class));
		this.port = SerialPort.getCommPort(portName);
		this.baudRate = baudRate;
		this.dataBits = dataBits;
		this.parity = parity;
		this.stopBits = (stopBits == 1) ? SerialPort.ONE_STOP_BIT : SerialPort.TWO_STOP_BITS;
	}

	public static String parityStr(int code) {
		return code == 0 ? "N" : (code == 1 ? "O" : (code == 2 ? "E" : "?"));
	}
	
	// this method must be synchronized with close()
	@Override
	synchronized protected boolean openPort() throws InterruptedException {
		if (Thread.currentThread().isInterrupted())
			throw new InterruptedException();
		if (port == null)
			return false;
		if (!port.isOpen()) {
			if (log.isInfoEnabled())
				log.info("Opening port: {}, {}, {}-{}-{}", port.getSystemPortName(), baudRate, dataBits, parityStr(parity), stopBits);
			try {
				if (!port.openPort()) {
					log.error("openPort() failed");
					close();
					Thread.sleep(1000);
					return false;
				} else if (!port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING, timeout, timeout)) {
					log.error("setComPortTimeouts() failed");
					close();
					return false;
				} else if (!port.setComPortParameters(baudRate, dataBits, stopBits, parity, false)) {
					log.error("setComPortParameters() failed");
					close();
					return false;
				}
			} catch (Exception e) {
				close();
				log.error("Error opening port {}: {}", port.getSystemPortName(), e);
			}
			if (log.isInfoEnabled())
				log.info("Port opened: {}", port.getSystemPortName());
		}
		return true;
	}

	// this method may be called from other thread
	@Override
	synchronized public void close() {
		if ((port != null) && port.isOpen()) {
			if (log.isInfoEnabled())
				log.info("Closing port: {}", port.getSystemPortName());
			try {
				port.closePort();
			} catch (Exception e) {
				log.error("Error closing port {}: {}", port.getSystemPortName(), e);
			}
			if (log.isInfoEnabled())
				log.info("Port {} closed", port.getSystemPortName());
		}
	}

	@Override
	protected void clearInput() {
		int bytes = port.bytesAvailable();
		while (bytes > 0) {
			int rb = port.readBytes(buffer, Math.max(bytes, buffer.length));
			if (rb < 0) {
				log.warn("readBytes() failed in clearInput()");
				return;
			}
			if ((rb > 0) && log.isWarnEnabled())
				log.warn("Unexpected input: " + ModbusPdu.toHex(buffer, 0, rb));
			bytes = port.bytesAvailable();
		}
	}

	@Override
	protected void sendData(int size) {
		int nb = port.writeBytes(buffer, size, 0);
		if (nb != size)
			log.warn("sendData() failed. {} from {} bytes written", nb, size);
	}

	@Override
	protected boolean readToBuffer(int start, int length, ModbusClient modbusClient) throws InterruptedException {
		int res = port.readBytes(buffer, length, start);
		if (res < length) {
			if ((res > 0) && log.isTraceEnabled())
				log.trace("Read (incomplete): " + ModbusPdu.toHex(buffer, 0, start + res));
			log.warn("Response from {} timeout ({} bytes, need {})", modbusClient.getServerId(), start + res, expectedBytes);
			return false;
		} else
			return true;
	}

}
