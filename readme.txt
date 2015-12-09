modbus-mini - Simple Modbus protocol implementation in Java 
https://github.com/sp20/modbus-mini/

Modbus RTU, TCP and UDP clients with functions 1, 2, 3, 4, 5, 6, 15, 16 are implemented at the moment.

For Modbus RTU You can choose from two serial communication libraries: RXTX (nrjavaserial) or jSSC.

Dependencies:
  - slf4j (http://www.slf4j.org/)
  - nrjavaserial (https://code.google.com/p/nrjavaserial/) or jSSC (http://code.google.com/p/java-simple-serial-connector/) - only for modbus RTU 


Place these jar-files in lib folder:

logback-classic-1.0.9.jar
logback-core-1.0.9.jar
nrjavaserial-3.8.8.jar
jssc-2.8.0.jar
slf4j-api-1.7.2.jar

If You're using newer versions, then change BuildPath in Eclipse.
