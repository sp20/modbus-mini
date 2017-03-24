package certa.modbus;

public final class ModbusConstants {

	private ModbusConstants() {}
	
	public static final int MAX_PDU_SIZE = 253;
	public static final int MAX_READ_COILS = 2000;
	public static final int MAX_READ_REGS = 125;
	public static final int MAX_WRITE_COILS = 1968;
	public static final int MAX_WRITE_REGS = 123;

	public static final byte FN_READ_COILS = 1;
	public static final byte FN_READ_DISCRETE_INPUTS = 2;
	public static final byte FN_READ_HOLDING_REGISTERS = 3;
	public static final byte FN_READ_INPUT_REGISTERS = 4;
	public static final byte FN_WRITE_SINGLE_COIL = 5;
	public static final byte FN_WRITE_SINGLE_REGISTER = 6;
	public static final byte FN_WRITE_MULTIPLE_COILS = 15;
	public static final byte FN_WRITE_MULTIPLE_REGISTERS = 16;
	
}
