package certa.modbus.samples;

import com.fazecast.jSerialComm.SerialPort;

import certa.modbus.client.ModbusClient;
import certa.modbus.client.RtuTransportJSerialComm;

public class SerialClientJSerialComm {

	public static void main(String[] args) {
	
		ModbusClient mc = new ModbusClient();
		mc.setTransport(new RtuTransportJSerialComm("COM9", 38400, 8, SerialPort.NO_PARITY, SerialPort.ONE_STOP_BIT, 1000, 5));
		
		mc.InitReadHoldingsRequest(1, 0, 10);

		try {
			mc.execRequest();
			if (mc.getResult() == ModbusClient.RESULT_OK)
				for (int i = 0; i < mc.getResponseCount(); i++)
					System.out.println("HR" + i + "=" + mc.getResponseRegister(mc.getResponseAddress() + i, false));
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			mc.close();
		}
		
	}

}
