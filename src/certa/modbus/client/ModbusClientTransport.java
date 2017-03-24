package certa.modbus.client;

public interface ModbusClientTransport {
	public void sendRequest(ModbusClient modbusClient) throws Exception, InterruptedException;
	public int waitResponse(ModbusClient modbusClient) throws Exception, InterruptedException;
	public void close();
}
