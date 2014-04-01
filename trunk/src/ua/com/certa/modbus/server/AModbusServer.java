package ua.com.certa.modbus.server;

import org.slf4j.Logger;

public abstract class AModbusServer {

	public final RegistersTable inputs;
	public final RegistersTable coils;
	public final RegistersTable iregs;
	public final RegistersTable hregs;

	final Logger log = getLog();
	
	protected ModbusWriteHandler handler;

	public AModbusServer(int inputsStart, int inputsCount, int coilsStart, int coilsCount,
			int iregsStart, int iregsCount, int hregsStart, int hregsCount) 
	{
		inputs = new RegistersTable(inputsStart, inputsCount);
		coils = new RegistersTable(coilsStart, coilsCount);
		iregs = new RegistersTable(iregsStart, iregsCount);
		hregs = new RegistersTable(hregsStart, hregsCount);
	}
	
	public void setWriteHandler(ModbusWriteHandler handler) {
		this.handler = handler;  
	}

	protected abstract Logger getLog();
	public abstract void Start();
	public abstract void Stop();
	
}
