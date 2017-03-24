// This class was not thoroughly tested! 

package certa.modbus.client;

import java.io.IOException;
import java.io.InputStream;

import org.slf4j.LoggerFactory;

import certa.modbus.ModbusPdu;
import gnu.io.CommPortIdentifier;
import gnu.io.NoSuchPortException;
import gnu.io.PortInUseException;
import gnu.io.SerialPort;
import gnu.io.UnsupportedCommOperationException;

public class RtuTransportRxtx extends AbstractRtuTransport {

	private final String portName;
	private final int baudRate;
	private final int dataBits;
	private final int parity;
	private final int stopBits;
	private SerialPort port;
	
	public RtuTransportRxtx(String portName, int baudRate, int dataBits, int parity, int stopBits, int timeout, int pause) {
		super(timeout, pause, true, LoggerFactory.getLogger(RtuTransportRxtx.class));
		this.portName = portName;
		this.baudRate = baudRate;
		this.dataBits = dataBits;
		this.parity = parity;
		this.stopBits = stopBits;
	}

	// this method must be synchronized with close()
	@Override
	synchronized protected void openPort() throws NoSuchPortException, PortInUseException, UnsupportedCommOperationException {
		if (port != null)
			return;
		log.info("Opening port: " + portName);
		CommPortIdentifier ident = CommPortIdentifier.getPortIdentifier(portName);
		port = ident.open("ModbusRtuClient on " + portName, 2000);
		port.setOutputBufferSize(buffer.length);
		port.setInputBufferSize(buffer.length);
		try {
			port.setSerialPortParams(baudRate, dataBits, stopBits, parity);				
			port.enableReceiveTimeout(timeout);
			port.setFlowControlMode(SerialPort.FLOWCONTROL_NONE);
		} catch (UnsupportedCommOperationException e) {
			close();
			throw e;
		}
		log.info("Port opened: " + port.getName());
	}

	@Override
	synchronized public void close() {
		SerialPort t = port;
		port = null;
		if (t != null) {
			log.info("Closing port: " + t.getName());
			t.close();
			log.info("Port closed: " + portName);
		}
	}

	public void clearInput() throws IOException {
		InputStream in = port.getInputStream();
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
		port.getOutputStream().write(buffer, 0, size);
		port.getOutputStream().flush();
	}

	@Override
	protected boolean readToBuffer(int start, int length, ModbusClient modbusClient) throws IOException {
		InputStream in = port.getInputStream();
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
				log.trace("Read (incomplete): " + ModbusPdu.toHex(buffer, 0, start + res));
			log.warn("Response from {} timeout ({} bytes, need {})", modbusClient.getServerId(), start + res, expectedBytes);
			return false;
		}
		else
			return true;
	}

}
