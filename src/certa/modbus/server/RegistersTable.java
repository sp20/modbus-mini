package certa.modbus.server;

public class RegistersTable {

	private final int[] values;
	protected final int start;
	
	public RegistersTable(int startAddress, int count) {
		this.start = startAddress;
		this.values = new int[count];
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

	public int count() {
		return values.length;
	}
	
	synchronized public int getInt(int address) {
		return values[address - start];
	}
	
	synchronized public void setInt(int address, int value) {
		values[address - start] = value;
	}

	synchronized public boolean getBool(int address) {
		return (values[address - start] != 0);
	}
	
	synchronized public void setBool(int address, boolean value) {
		values[address - start] = value ? 1 : 0;
	}
	
}
