package ua.com.certa.modbus.samples;

import java.util.Scanner;

import ua.com.certa.modbus.client.AModbusClient;
import ua.com.certa.modbus.client.ModbusTcpClient;

public class TcpClientSample {

	public static void main(String[] args) {
	
		ModbusTcpClient mc = new ModbusTcpClient("localhost", 502, null, 0, 1000, 300, 100, true);
	
		try {
			mc.InitReadHoldingsRequest(1, 0, 10);
			mc.execRequest();
			if (mc.getResult() == AModbusClient.RESULT_OK)
				for (int i = 0; i < mc.getResponseCount(); i++)
					System.out.println("HR" + i + "=" + mc.getResponseRegister(mc.getResponseAddress() + i));

			mc.InitWriteRegisterRequest(1, 2, 321);
			mc.execRequest();

			mc.InitReadHoldingsRequest(1, 0, 10);
			mc.execRequest();
			if (mc.getResult() == AModbusClient.RESULT_OK)
				for (int i = 0; i < mc.getResponseCount(); i++)
					System.out.println("HR" + i + "=" + mc.getResponseRegister(mc.getResponseAddress() + i));
			
			System.out.println("Press Enter to exit");
			System.out.println("");
			Scanner in = new Scanner(System.in);
			in.nextLine();      
			in.close();

		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			mc.close();
		}
	}

}
