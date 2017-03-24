package certa.modbus.server;

import certa.modbus.ModbusPdu;

public interface RequestProcessor {

	/**
	 * @param pdu - contains request data. Response should be stored here.
	 * @return <b>true</b> if request was processed and response is ready in pdu 
	 */
	public boolean processRequest(ModbusPdu pdu);
	
}
