package certa.modbus.server;

import org.slf4j.Logger;

import certa.modbus.ModbusPdu;

public abstract class AModbusServer {

	public final RegistersTable inputs;
	public final RegistersTable coils;
	public final RegistersTable iregs;
	public final RegistersTable hregs;
	public final RequestProcessor processor; // cannot be null

	final Logger log = getLog();
	
	protected ModbusWriteHandler handler;
	
	public AModbusServer(int inputsStart, int inputsCount, int coilsStart, int coilsCount,
			int iregsStart, int iregsCount, int hregsStart, int hregsCount, RequestProcessor processor) 
	{
		this.inputs = new RegistersTable(inputsStart, inputsCount);
		this.coils = new RegistersTable(coilsStart, coilsCount);
		this.iregs = new RegistersTable(iregsStart, iregsCount);
		this.hregs = new RegistersTable(hregsStart, hregsCount);
		this.processor = (processor != null) ? processor : new DefaultRequestProcessor(this); 
	}
	
	public void setWriteHandler(ModbusWriteHandler handler) {
		this.handler = handler;  
	}

	public void logData(String prefix, byte[] buffer, int start, int length) {
		if (log.isTraceEnabled()) 
			log.trace(prefix + ModbusPdu.toHex(buffer, start, length));
	}
	
	protected abstract Logger getLog();
	public abstract void Start();
	public abstract void Stop();
	
}
