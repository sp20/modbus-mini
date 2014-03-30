package ua.com.certa.modbus.server;

public class IntTable extends ATable {

	private final int[] values;
	
	public IntTable(int startAddress, int count) {
		super(startAddress);
		this.values = new int[count];
	}
	
	@Override
	public int count() {
		return values.length;
	}
	
	synchronized public int get(int address) {
		return values[address - start];
	}
	
	synchronized public void set(int address, int value) {
		values[address - start] = value;
	}

}
