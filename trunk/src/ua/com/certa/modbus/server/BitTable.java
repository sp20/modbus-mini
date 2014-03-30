package ua.com.certa.modbus.server;

public class BitTable extends ATable {

	private final boolean[] values;
	
	public BitTable(int startAddress, int count) {
		super(startAddress);
		this.values = new boolean[count];
	}
	
	@Override
	public int count() {
		return values.length;
	}
	
	synchronized public boolean get(int address) {
		return values[address - start];
	}
	
	synchronized public void set(int address, boolean value) {
		values[address - start] = value;
	}
	

}
