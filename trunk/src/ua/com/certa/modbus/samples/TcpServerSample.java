package ua.com.certa.modbus.samples;

import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ua.com.certa.modbus.server.AModbusServer;
import ua.com.certa.modbus.server.ModbusTcpServer;
import ua.com.certa.modbus.server.ModbusWriteHandler;

public class TcpServerSample implements ModbusWriteHandler {

	private static final Logger log = LoggerFactory.getLogger("MODBUS SERVER");
	
	static ModbusWriteHandler handler = new TcpServerSample();
	static ModbusTcpServer ms = new ModbusTcpServer(null, 502, 10000, 0, 65535, 0, 65535, 0, 65535, 0, 65535, handler);
	
	public static void main(String[] args) {

		System.out.println("Press Enter to exit");
		System.out.println("");

		ms.Start();

		ms.hregs.set(3, 123);
		
		Scanner in = new Scanner(System.in);
		in.nextLine();      
		in.close();
		
		ms.Stop();

	}

	@Override
	public boolean OnWriteCoil(AModbusServer server, int address, boolean value) {
		log.info("Coil {} is set to {}", address, value);
		server.coils.set(address, value);
		return true;
	}

	@Override
	public boolean OnWriteHReg(AModbusServer server, int address, int value) {
		log.info("Holding {} is set to {}", address, value);
		server.hregs.set(address, value);
		return true;
	}

}
