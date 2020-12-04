modbus-mini - Simple Modbus protocol implementation for Java 1.7+
https://github.com/sp20/modbus-mini/

Modbus RTU, TCP and UDP clients with functions 1, 2, 3, 4, 5, 6, 15, 16 are implemented at the moment.

For Modbus RTU there are three possible options:

  - jSerialComm (https://fazecast.github.io/jSerialComm/) - recommended.

  - jSSC (https://github.com/scream3r/java-simple-serial-connector) - is very stable and has worked in real 
    systems for years, but its development stopped and it does not work with the latest updates of JRE 8.

  - nrjavaserial (https://github.com/NeuronRobotics/nrjavaserial) - was not tested in long-running applications.

For logging the slf4j is used (http://www.slf4j.org/)

The code is working in real systems since 2014 on Windows and Linux (Ubuntu, Debian, Raspbian).

--------------------------------------------------------------------------------------------------------------

There is also a simple but handy console Modbus RTU sniffer.

https://github.com/sp20/modbus-mini/tree/master/release/modbus-sniffer.jar

Just connect the RS485/USB adapter to the existing Modbus RTU network and start the command:

In Windows:
java -jar modbus-sniffer.jar COM1 38400 N 2 modbus-log.txt

In Linux (the user must be in dialout group):
java -jar modbus-sniffer.jar /dev/ttyUSB0 38400 N 2 modbus-log.txt
