package ua.com.certa.modbus.server;

abstract public class ATable {

	protected final int start;
	
	public ATable(int startAddress) {
		this.start = startAddress;
	}
	
	public boolean isValidAddress(int address) {
		return (address >= firstAddress()) && (address <= lastAddress());
	}
	
	public int firstAddress() {
		return start;
	}
	
	public int lastAddress() {
		return start + count() - 1;
	}
	
	abstract public int count();
	
}
