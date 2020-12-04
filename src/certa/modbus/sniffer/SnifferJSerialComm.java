package certa.modbus.sniffer;

import static certa.modbus.ModbusConstants.*;

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;

import com.fazecast.jSerialComm.SerialPort;

import certa.modbus.ModbusPdu;

public class SnifferJSerialComm {

	static SerialPort port;
	static protected final byte[] buffer = new byte[65536];
	static protected final long[] bufferTimes = new long[65536];
	static int bufSize = 0;
	static int processedSize = 0;
	static boolean processedOk = false;
	static boolean needMoreBytes = false;
	
	static void freeBuffer() {
		if (processedSize > 0) {
			if (!processedOk)
				logLine(bufferTimes[processedSize - 1], "Unknown data: " + ModbusPdu.toHex(buffer, 0, processedSize));
			processedOk = false;
			bufSize -= processedSize;
			if (bufSize < 0)
				bufSize = 0;
			for (int i = 0; i < bufSize; i++) {
				buffer[i] = buffer[processedSize + i];
				bufferTimes[i] = bufferTimes[processedSize + i];
			}
		}
		processedSize = 0;
	}
	
	static protected boolean readData() {
		int avail = Math.min(port.bytesAvailable(), buffer.length - bufSize);
		boolean res = false;
		while (avail > 0) {
			res = true;
			int br = port.readBytes(buffer, avail, bufSize);
			// Save receive time for each byte
			long time = System.currentTimeMillis();
			for (int i = 0; i < br; i++)
				bufferTimes[bufSize + i] = time;
			bufSize += br;
			avail = Math.min(port.bytesAvailable(), buffer.length - bufSize);
		}
		return res;
	}

	static void processRequest(int id, int func, boolean inResponse) {
		String fstr = "";
		if (func == 1)
			fstr = "1 (Read Coils)";
		else if (func == 2)
			fstr = "2 (Read DI)";
		else if (func == 3)
			fstr = "3 (Read HR)";
		else if (func == 4)
			fstr = "4 (Read IR)";
		else if (func == 5)
			fstr = "5 (Write Single Coil)";
		else if (func == 6)
			fstr = "6 (Write Single HR)";
		else if (func == 15)
			fstr = "15 (Write Multiple Coils)";
		else if (func == 16)
			fstr = "16 (Write Multiple HRs)";

		if ((func == 1) || (func == 2) || (func == 3) || (func == 4)) {
			if (bufSize < 8) {
				needMoreBytes = true;
				return;
			}
			int addr = ModbusPdu.bytesToInt16(buffer[3], buffer[2], true);
			int count = ModbusPdu.bytesToInt16(buffer[5], buffer[4], true);
			int crc = ModbusPdu.bytesToInt16(buffer[6], buffer[7], true);
			int calcCrc = ModbusPdu.calcCRC16(buffer, 0, 6);
			if (crc != calcCrc) {
				if (!inResponse)
					processedSize = 1;
				return;
			}
			long t = bufferTimes[0];
			logLine(0, "");
			logLine(t, "Request: " + ModbusPdu.toHex(buffer, 0, 8));
			logLine(t, "ID: " + id + ", Fn: " + fstr + ", addr: " + addr + ", count: " + count + ", CRC: 0x" + Integer.toHexString(crc));
			processedSize = 8;
			processedOk = true;
			int max_count = (func < 3) ? MAX_READ_COILS : MAX_READ_REGS;
			if ((count < 1) || (count > max_count)) {
				logLine(t, "BAD REGISTERS COUNT: " + count);
				waitingResponse = true;
				return;
			} else if (id == 255) {
				// Broadcast - no response
				waitingResponse = false;
				return;
			} else {
				// Ready for response
				waitingResponse = true;
				respId = id;
				respFunction = func;
				int size = (func < 3) ? ModbusPdu.bytesCount(count) : (count * 2);
				respSize = size + 5;
				return;
			}
		} else if ((func == 5) || (func == 6)) {
			if (bufSize < 8) {
				needMoreBytes = true;
				return;
			}
			int addr = ModbusPdu.bytesToInt16(buffer[3], buffer[2], true);
			int value = ModbusPdu.bytesToInt16(buffer[5], buffer[4], false);
			int crc = ModbusPdu.bytesToInt16(buffer[6], buffer[7], true);
			int calcCrc = ModbusPdu.calcCRC16(buffer, 0, 6);
			if (crc != calcCrc) {
				if (!inResponse)
					processedSize = 1;
				return;
			}
			long t = bufferTimes[0];
			logLine(0, "");
			logLine(t, "Request: " + ModbusPdu.toHex(buffer, 0, 8));
			logLine(t, "ID: " + id + ", Fn: " + fstr + ", addr: " + addr + ", value: " + value + ", CRC: 0x" + Integer.toHexString(crc));
			processedSize = 8;
			processedOk = true;
			if (id == 255) {
				// Broadcast - no response
				waitingResponse = false;
				return;
			} else {
				// Ready for response
				waitingResponse = true;
				respId = id;
				respFunction = func;
				respSize = 8;
				return;
			}
		} else if ((func == 15) || (func == 16)) {
			if (bufSize < 10) {
				needMoreBytes = true;
				return;
			}
			int addr = ModbusPdu.bytesToInt16(buffer[3], buffer[2], true);
			int count = ModbusPdu.bytesToInt16(buffer[5], buffer[4], true);
			int bytes = ((int) buffer[6]) & 0xFF;
			if (bufSize < (9 + bytes)) {
				needMoreBytes = true;
				return;
			}
			int crc = ModbusPdu.bytesToInt16(buffer[7 + bytes], buffer[8 + bytes], true);
			int calcCrc = ModbusPdu.calcCRC16(buffer, 0, 7 + bytes);
			if (crc != calcCrc) {
				if (!inResponse)
					processedSize = 1;
				return;
			}
			long t = bufferTimes[0];
			logLine(0, "");
			logLine(t, "Request: " + ModbusPdu.toHex(buffer, 0, 9 + bytes));
			logLine(t, "ID: " + id + ", Fn: " + fstr + ", addr: " + addr + ", count: " + count + ", bytes: " + bytes + ", CRC: 0x" + Integer.toHexString(crc));
			processedSize = 9 + bytes;
			processedOk = true;
			if (id == 255) {
				// Broadcast - no response
				waitingResponse = false;
				return;
			} else {
				// Ready for response
				waitingResponse = true;
				respId = id;
				respFunction = func;
				respSize = 8;
				return;
			}
		} else {
			processedSize = 1;
			return;
		}
	}
	
	static boolean waitingResponse = false;
	static int respSize = 0;
	static int respFunction = 0;
	static int respId = 0;
	
	static void processResponse(int id, int func) {
		if ((id != respId) || ((func & 0x7F) != respFunction)) {
			// Bad response id or function
			waitingResponse = false;
			// May be it was a timeout and there is another request in the buffer
			processRequest(id, func, true);
			return;
		}
		if ((func & 0x80) != 0) {
			// Exception
			if (bufSize < 5) {
				needMoreBytes = true;
				return;
			}
			logLine(bufferTimes[0], "Response: " + ModbusPdu.toHex(buffer, 0, 5));
			System.out.println("EXCEPTION from " + id + ", Code: " + buffer[2]);
			waitingResponse = false;
			processedSize = 5;
			processedOk = true;
			return;
		}
		if (bufSize < respSize) {
			// Wait for more bytes, but also it could be a timeout and there is 
			// another request (with same id and function) in the buffer
			needMoreBytes = true;
			processRequest(id, func, true);
			return;
		}
		waitingResponse = false; // Full response is in the buffer. Only request can be after it
		int crc, calcCrc;
		if ((func == 1) || (func == 2) || (func == 3) || (func == 4)) {
			int size = ((int) buffer[2]) & 0xFF;
			if (size != (respSize - 5)) {
				// Bad response size, but it could be a request
				processRequest(id, func, false);
				return;
			}
			crc = ModbusPdu.bytesToInt16(buffer[size+3], buffer[size+4], true);
			calcCrc = ModbusPdu.calcCRC16(buffer, 0, size+3);
		} else if ((func == 5) || (func == 6) || (func == 15) || (func == 16)) {
			crc = ModbusPdu.bytesToInt16(buffer[6], buffer[7], true);
			calcCrc = ModbusPdu.calcCRC16(buffer, 0, 6);
		} else {
			// unknown function
			crc = 1;
			calcCrc = 2;
		}
		if (crc != calcCrc) {
			return;
		}
		logLine(bufferTimes[0], "Response: " + ModbusPdu.toHex(buffer, 0, respSize));
		//System.out.println("ID: " + id + ", Fn: " + func);
		processedSize = respSize;
		processedOk = true;
	}

	static PrintWriter printWriter = null;
	static long timeStart = 0;
	
	static void logLine(long time, String msg) {
		long t = time - timeStart;
		long h = t / (1000 * 60 * 60);
		long m = (t % (1000 * 60 * 60)) / (1000 * 60);
		long s = (t % (1000 * 60)) / 1000;
		long ms = t % 1000;
		String line = "";
		if ((msg != null) && (msg.length() > 0))
			line = String.format("%02d:%02d:%02d.%03d - %s", h, m, s, ms, msg);
		System.out.println(line);
		if (printWriter != null)
			printWriter.println(line);
	}
	
	public static void main(String[] args) {
	
		if (args.length < 4) {
			System.out.println("Usage: modbus-niffer <port> <speed> <parity_O_E_N> <stop_bits> [file_name.txt]");
			return;
		}
		
		if (args.length >= 5) {
			String fname = args[4];
			try {
				printWriter = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(fname, true), "US-ASCII")), true);
			} catch (FileNotFoundException e) {
				System.out.println("Incorrect file name: " + fname);
				return;
			} catch (UnsupportedEncodingException e) {
				System.out.println("Unsupported text encoding");
				return;
			} 
		}

		port = SerialPort.getCommPort(args[0]);
		try {
			int baud = Integer.parseInt(args[1]);
			String s = args[2];
			int parity = "O".equals(s) ? SerialPort.ODD_PARITY : ("E".equals(s) ? SerialPort.EVEN_PARITY : SerialPort.NO_PARITY);
			int stop = "2".equals(args[3]) ? SerialPort.TWO_STOP_BITS : SerialPort.ONE_STOP_BIT;
			logLine(0, "");
			logLine(0, "");
			timeStart = System.currentTimeMillis();
			logLine(timeStart, String.format("Opening port %s, %d-8-%s-%s\n", args[0], baud, s, args[3]));
			port.openPort();
			port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
			port.setComPortParameters(baud, 8, stop, parity, false);
			while(true) {
				// Read all arrived bytes
				if (!readData()) {
					Thread.sleep(1);
					continue;
				}
				// Parsing all bytes in the buffer
				needMoreBytes = false;
				while (bufSize >= 2) {
					int id = buffer[0];
					int func = buffer[1];
					if (waitingResponse)
						processResponse(id, func);
					else
						processRequest(id, func, false);
					// Drop old bytes
					freeBuffer();
					// Go out and wait for more bytes if needed
					if (needMoreBytes)
						break;
				}
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		} finally {
			logLine(System.currentTimeMillis(), "Closing port " + args[0]);
			logLine(0, "");
			logLine(0, "");
			if (port.isOpen())
				port.closePort();
		}
	}

}
