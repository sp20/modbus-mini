package ua.com.certa.modbus.samples;

import gnu.io.SerialPort;
import ua.com.certa.modbus.client.AModbusClient;
import ua.com.certa.modbus.client.ModbusRtuClientRxtx;

public class SerialClientRxtx {

	public static void main(String[] args) {
	
		ModbusRtuClientRxtx mc = new ModbusRtuClientRxtx("COM1", 9600, 8, SerialPort.PARITY_EVEN, SerialPort.STOPBITS_1, 1000, 5);
	
		mc.InitReadHoldingsRequest(1, 0, 10);

		try {
			mc.execRequest();
			if (mc.getResult() == AModbusClient.RESULT_OK)
				for (int i = 0; i < mc.getResponseCount(); i++)
					System.out.println("HR" + i + "=" + mc.getResponseRegister(mc.getResponseAddress() + i));
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			mc.close();
		}
		
	}

}
