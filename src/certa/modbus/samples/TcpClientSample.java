package certa.modbus.samples;

import certa.modbus.client.ModbusClient;
import certa.modbus.client.TcpTransport;

public class TcpClientSample {

	public static void main(String[] args) {
	
		ModbusClient mc = new ModbusClient();
		mc.setTransport(new TcpTransport("localhost", 502, null, 0, 1000, 300, 100, true));
		
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
