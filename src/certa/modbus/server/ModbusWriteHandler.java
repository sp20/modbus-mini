package certa.modbus.server;

public interface ModbusWriteHandler {
	// Caution: These methods will be called from various threads.
	// method must return true on success
	public boolean OnWriteCoil(AModbusServer server, int address, boolean value);
	public boolean OnWriteHReg(AModbusServer server, int address, int value);
}
