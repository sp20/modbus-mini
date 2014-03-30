package ua.com.certa.modbus.server;

import org.slf4j.Logger;

public abstract class AModbusServer {

	public final BitTable inputs;
	public final BitTable coils;
	public final IntTable iregs;
	public final IntTable hregs;

	final Logger log = getLog();
	
	final ModbusWriteHandler handler;

	public AModbusServer(int inputsStart, int inputsCount, int coilsStart, int coilsCount,
			int iregsStart, int iregsCount, int hregsStart, int hregsCount, ModbusWriteHandler handler) 
	{
		inputs = new BitTable(inputsStart, inputsCount);
		coils = new BitTable(coilsStart, coilsCount);
		iregs = new IntTable(iregsStart, iregsCount);
		hregs = new IntTable(hregsStart, hregsCount);
		this.handler = handler;  
	}

	protected abstract Logger getLog();
	public abstract void Start();
	public abstract void Stop();
	
}
