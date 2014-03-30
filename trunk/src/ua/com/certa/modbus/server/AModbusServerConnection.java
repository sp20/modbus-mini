package ua.com.certa.modbus.server;

import ua.com.certa.modbus.AModbus;
import ua.com.certa.modbus.ModbusUtils;

public abstract class AModbusServerConnection extends AModbus {

	protected final AModbusServer server;
	
	public AModbusServerConnection(AModbusServer server) 
	{
		this.server = server;
	}
	
	protected void logData(String prefix, byte[] buffer, int start, int length) {
		if (server.log.isTraceEnabled()) 
			server.log.trace(prefix + ModbusUtils.toHex(buffer, start, length));
	}
	
	private void setException(int code) {
		server.log.debug("Sending exception {}", code);
		setPduSize(2);
		writeByteToPDU(0, (byte)(readByteFromPDU(0) | 0x80));
		writeByteToPDU(1, (byte)code);
	}
	
	private boolean validRange(int startAddr, int count, ATable table, String name, int maxCount) {
		if ((count < 1) || (count > maxCount)) {
			server.log.warn("Invalid {} count ({}). Must be 1..{}", name, count, maxCount);
			setException(3);
			return false;
		}
		int endAddr = startAddr + count - 1; 
		if (!table.isValidAddress(startAddr) || !table.isValidAddress(endAddr)) {
			server.log.warn("Invalid {} range ({} - {}). Can be {} - {}", 
					name, startAddr, endAddr, table.firstAddress(), table.lastAddress());
			setException(2);
			return false;
		}
		return true;
	}
	
	private boolean validPduSize(int expectedSize) {
		if ((getPduSize() < expectedSize)) {
			server.log.warn("Invalid PDU size ({}). Must be {}", getPduSize(), expectedSize);
			setPduSize(0); // don't respond
			return false;
		}
		return true;
	}
	
	private void processReadBits(BitTable table, String name) {
		int addr = readInt16FromPDU(1);
		int count = readInt16FromPDU(3);
		server.log.debug("Read {}: addr={}, count={}", name, addr, count);
		if (!validRange(addr, count, table, name, MAX_READ_COILS))
			return;
		int nBytes = bytesCount(count);
		setPduSize(2 + nBytes);
		writeByteToPDU(1, (byte)nBytes);
		for (int i = 0; i < nBytes; i++)
			writeByteToPDU(2 + i, (byte)0);
		for (int i = 0; i < count; i++)
			writeBitToPDU(2, i, table.get(i));
	}
	
	private void processReadInts(IntTable table, String name) {
		int addr = readInt16FromPDU(1);
		int count = readInt16FromPDU(3);
		server.log.debug("Read {}: addr={}, count={}", name, addr, count);
		if (!validRange(addr, count, table, name, MAX_READ_REGS))
			return;
		int nBytes = count * 2;
		setPduSize(2 + nBytes);
		writeByteToPDU(1, (byte)nBytes);
		for (int i = 0; i < count; i++) {
			writeInt16ToPDU(2 + i * 2, table.get(addr + i));
		}
		
	}
	
	private void processWriteSingleCoil() {
		int addr = readInt16FromPDU(1);
		int value = readInt16FromPDU(3) & 0xFFFF;
		server.log.debug("Write single coil {}, value: {}", addr, value);
		if ((value != 0) & (value != 0xFF00)) {
			server.log.warn("Invalid write coil value ({}). Must be 0 or 0xFF00 (65280)", value);
			setException(3);
			return;
		}
		if (!server.coils.isValidAddress(addr)) {
			server.log.warn("Invalid write coil address ({})", addr);
			setException(2);
			return;
		}
		if ((server.handler == null) || !server.handler.OnWriteCoil(server, addr, (value > 0))) {
			server.log.warn("Writing coil {} failed", addr);
			setException(4);
			return;
		}
		setPduSize(5); // response equals to request
	}
	
	private void processWriteSingleHReg() {
		int addr = readInt16FromPDU(1);
		int value = readInt16FromPDU(3);
		server.log.debug("Write single register {}, value: {}", addr, value);
		if (!server.hregs.isValidAddress(addr)) {
			server.log.warn("Invalid write reg address ({})", addr);
			setException(2);
			return;
		}
		if ((server.handler == null) || !server.handler.OnWriteHReg(server, addr, value)) {
			server.log.warn("Writing register {} failed", addr);
			setException(4);
			return;
		}
		setPduSize(5); // response equals to request
	}
	
	private void processWriteCoils() {
		int addr = readInt16FromPDU(1);
		int count = readInt16FromPDU(3);
		int nBytes = readByteFromPDU(5);
		server.log.debug("Write multiple coils: addr={}, count={}", addr, count);
		if (!validPduSize(6 + nBytes))
			return;
		if (!validRange(addr, count, server.coils, "coils", MAX_WRITE_COILS))
			return;
		if (nBytes != bytesCount(count)) {
			server.log.warn("Write byte count (N={}) doesn't match coils count ({}). N must be {}", nBytes, count, bytesCount(count));
			setException(3);
			return;
		}
		boolean fail = (server.handler == null);
		for (int i = 0; !fail && (i < count); i++)
			fail = !server.handler.OnWriteCoil(server, addr + i, readBitFromPDU(6, i));
		if (fail) {
			server.log.warn("Writing coils at {}, count {} failed", addr, count);
			setException(4);
			return;
		}
		setPduSize(5); // response is first 5 bytes of request
	}
	
	private void processWriteHRegs() {
		int addr = readInt16FromPDU(1);
		int count = readInt16FromPDU(3);
		int nBytes = readByteFromPDU(5);
		server.log.debug("Write multiple reg-s: addr={}, count={}", addr, count);
		if (!validPduSize(6 + nBytes))
			return;
		if (!validRange(addr, count, server.hregs, "holding reg-s", MAX_WRITE_REGS))
			return;
		if (nBytes != 2 * count) {
			server.log.warn("Write byte count (N={}) doesn't match reg-s count ({}). N must be {}", nBytes, count, 2 * count);
			setException(3);
			return;
		}
		boolean fail = (server.handler == null);
		for (int i = 0; !fail && (i < count); i++)
			fail = !server.handler.OnWriteHReg(server, addr + i, readInt16FromPDU(6 + i * 2));
		if (fail) {
			server.log.warn("Writing reg-s at {}, count {} failed", addr, count);
			setException(4);
			return;
		}
		setPduSize(5); // response is first 5 bytes of request
	}
	
	// returns true if response must be sent
	protected boolean processRequest() { 
		if (getPduSize() < 5) {
			server.log.error("Invalid PDU size ({} < 5)", getPduSize());
			return false;
		}
		int func = readByteFromPDU(0);
		if (func == FN_READ_COILS)
			processReadBits(server.coils, "coils");
		else if (func == FN_READ_DISCRETE_INPUTS)
			processReadBits(server.inputs, "inputs");
		else if (func == FN_READ_HOLDING_REGISTERS)
			processReadInts(server.hregs, "holding reg-s");
		else if (func == FN_READ_INPUT_REGISTERS)
			processReadInts(server.iregs, "input reg-s");
		else if (func == FN_WRITE_SINGLE_COIL)
			processWriteSingleCoil();
		else if (func == FN_WRITE_SINGLE_REGISTER)
			processWriteSingleHReg();
		else if (func == FN_WRITE_MULTIPLE_COILS)
			processWriteCoils();
		else if (func == FN_WRITE_MULTIPLE_REGISTERS)
			processWriteHRegs();
		else {
			server.log.error("Unknown function: {}", func);
			setException(1);
		}
		return (getPduSize() > 0);
	}
	
}
