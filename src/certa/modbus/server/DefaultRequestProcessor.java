package certa.modbus.server;

import static certa.modbus.ModbusConstants.*;

import certa.modbus.ModbusPdu;

public class DefaultRequestProcessor implements RequestProcessor {

	public final AModbusServer server;
	
	public DefaultRequestProcessor(AModbusServer server) 
	{
		this.server = server;
	}
	
	protected void setException(ModbusPdu pdu, int code) {
		server.log.debug("Sending exception {}", code);
		pdu.setPduSize(2);
		pdu.writeByteToPDU(0, (byte)(pdu.readByteFromPDU(0) | 0x80));
		pdu.writeByteToPDU(1, (byte)code);
	}
	
	private boolean validRange(ModbusPdu pdu, int startAddr, int count, RegistersTable table, String name, int maxCount) {
		if ((count < 1) || (count > maxCount)) {
			server.log.warn("Invalid {} count ({}). Must be 1..{}", name, count, maxCount);
			setException(pdu, 3);
			return false;
		}
		int endAddr = startAddr + count - 1; 
		if (!table.isValidAddress(startAddr) || !table.isValidAddress(endAddr)) {
			server.log.warn("Invalid {} range ({} - {}). Can be {} - {}", 
					name, startAddr, endAddr, table.firstAddress(), table.lastAddress());
			setException(pdu, 2);
			return false;
		}
		return true;
	}
	
	private boolean validPduSize(ModbusPdu pdu, int expectedSize) {
		if ((pdu.getPduSize() < expectedSize)) {
			server.log.warn("Invalid PDU size ({}). Must be {}", pdu.getPduSize(), expectedSize);
			pdu.setPduSize(0); // don't respond
			return false;
		}
		return true;
	}
	
	private void processReadBits(ModbusPdu pdu, RegistersTable table, String name) {
		int addr = pdu.readInt16FromPDU(1, true);
		int count = pdu.readInt16FromPDU(3, true);
		server.log.debug("Read {}: addr={}, count={}", name, addr, count);
		if (!validRange(pdu, addr, count, table, name, MAX_READ_COILS))
			return;
		int nBytes = ModbusPdu.bytesCount(count);
		pdu.setPduSize(2 + nBytes);
		pdu.writeByteToPDU(1, (byte)nBytes);
		for (int i = 0; i < nBytes; i++)
			pdu.writeByteToPDU(2 + i, (byte)0);
		for (int i = 0; i < count; i++)
			pdu.writeBitToPDU(2, i, table.getBool(addr + i));
	}
	
	private void processReadInts(ModbusPdu pdu, RegistersTable table, String name) {
		int addr = pdu.readInt16FromPDU(1, true);
		int count = pdu.readInt16FromPDU(3, true);
		server.log.debug("Read {}: addr={}, count={}", name, addr, count);
		if (!validRange(pdu, addr, count, table, name, MAX_READ_REGS))
			return;
		int nBytes = count * 2;
		pdu.setPduSize(2 + nBytes);
		pdu.writeByteToPDU(1, (byte)nBytes);
		for (int i = 0; i < count; i++) {
			pdu.writeInt16ToPDU(2 + i * 2, table.getInt(addr + i));
		}
		
	}
	
	private void processWriteSingleCoil(ModbusPdu pdu) {
		int addr = pdu.readInt16FromPDU(1, true);
		int value = pdu.readInt16FromPDU(3, true);
		server.log.debug("Write single coil {}, value: {}", addr, value);
		if ((value != 0) & (value != 0xFF00)) {
			server.log.warn("Invalid write coil value ({}). Must be 0 or 0xFF00 (65280)", value);
			setException(pdu, 3);
			return;
		}
		if (!server.coils.isValidAddress(addr)) {
			server.log.warn("Invalid write coil address ({})", addr);
			setException(pdu, 2);
			return;
		}
		if ((server.handler == null) || !server.handler.OnWriteCoil(server, addr, (value > 0))) {
			server.log.warn("Writing coil {} failed", addr);
			setException(pdu, 4);
			return;
		}
		pdu.setPduSize(5); // response equals to request
	}
	
	private void processWriteSingleHReg(ModbusPdu pdu) {
		int addr = pdu.readInt16FromPDU(1, true);
		int value = pdu.readInt16FromPDU(3, false);
		server.log.debug("Write single register {}, value: {}", addr, value);
		if (!server.hregs.isValidAddress(addr)) {
			server.log.warn("Invalid write reg address ({})", addr);
			setException(pdu, 2);
			return;
		}
		if ((server.handler == null) || !server.handler.OnWriteHReg(server, addr, value)) {
			server.log.warn("Writing register {} failed", addr);
			setException(pdu, 4);
			return;
		}
		pdu.setPduSize(5); // response equals to request
	}
	
	private void processWriteCoils(ModbusPdu pdu) {
		int addr = pdu.readInt16FromPDU(1, true);
		int count = pdu.readInt16FromPDU(3, true);
		int nBytes = pdu.readByteFromPDU(5, true);
		server.log.debug("Write multiple coils: addr={}, count={}", addr, count);
		if (!validPduSize(pdu, 6 + nBytes))
			return;
		if (!validRange(pdu, addr, count, server.coils, "coils", MAX_WRITE_COILS))
			return;
		int bytesCount = ModbusPdu.bytesCount(count);
		if (nBytes != bytesCount) {
			server.log.warn("Write byte count (N={}) doesn't match coils count ({}). N must be {}", nBytes, count, bytesCount);
			setException(pdu, 3);
			return;
		}
		boolean fail = (server.handler == null);
		for (int i = 0; !fail && (i < count); i++)
			fail = !server.handler.OnWriteCoil(server, addr + i, pdu.readBitFromPDU(6, i));
		if (fail) {
			server.log.warn("Writing coils at {}, count {} failed", addr, count);
			setException(pdu, 4);
			return;
		}
		pdu.setPduSize(5); // response is first 5 bytes of request
	}
	
	private void processWriteHRegs(ModbusPdu pdu) {
		int addr = pdu.readInt16FromPDU(1, true);
		int count = pdu.readInt16FromPDU(3, true);
		int nBytes = pdu.readByteFromPDU(5, true);
		server.log.debug("Write multiple reg-s: addr={}, count={}", addr, count);
		if (!validPduSize(pdu, 6 + nBytes))
			return;
		if (!validRange(pdu, addr, count, server.hregs, "holding reg-s", MAX_WRITE_REGS))
			return;
		if (nBytes != 2 * count) {
			server.log.warn("Write byte count (N={}) doesn't match reg-s count ({}). N must be {}", nBytes, count, 2 * count);
			setException(pdu, 3);
			return;
		}
		boolean fail = (server.handler == null);
		for (int i = 0; !fail && (i < count); i++)
			fail = !server.handler.OnWriteHReg(server, addr + i, pdu.readInt16FromPDU(6 + i * 2, false));
		if (fail) {
			server.log.warn("Writing reg-s at {}, count {} failed", addr, count);
			setException(pdu, 4);
			return;
		}
		pdu.setPduSize(5); // response is first 5 bytes of request
	}
	
	@Override
	public boolean processRequest(ModbusPdu pdu) { 
		if (pdu.getPduSize() < 5) {
			server.log.error("Invalid PDU size ({} < 5)", pdu.getPduSize());
			return false;
		}
		int func = pdu.getFunction();
		if (func == FN_READ_COILS)
			processReadBits(pdu, server.coils, "coils");
		else if (func == FN_READ_DISCRETE_INPUTS)
			processReadBits(pdu, server.inputs, "inputs");
		else if (func == FN_READ_HOLDING_REGISTERS)
			processReadInts(pdu, server.hregs, "holding reg-s");
		else if (func == FN_READ_INPUT_REGISTERS)
			processReadInts(pdu, server.iregs, "input reg-s");
		else if (func == FN_WRITE_SINGLE_COIL)
			processWriteSingleCoil(pdu);
		else if (func == FN_WRITE_SINGLE_REGISTER)
			processWriteSingleHReg(pdu);
		else if (func == FN_WRITE_MULTIPLE_COILS)
			processWriteCoils(pdu);
		else if (func == FN_WRITE_MULTIPLE_REGISTERS)
			processWriteHRegs(pdu);
		else {
			server.log.error("Unknown function: {}", func);
			setException(pdu, 1);
		}
		return true;
	}
	
}
