modbus-mini - Simple Modbus protocol implementation for Java 1.7+
https://github.com/sp20/modbus-mini/

Modbus RTU, TCP and UDP clients with functions 1, 2, 3, 4, 5, 6, 15, 16 are implemented at the moment.

For Modbus RTU the jSSC library is recommended (tested in real systems).
As an alternative you may use nrjavaserial (RXTX fork). This combination was not tested in long-running applications.

Dependencies:
  - slf4j (http://www.slf4j.org/)
  - jSSC (https://github.com/scream3r/java-simple-serial-connector)
  - nrjavaserial (https://github.com/NeuronRobotics/nrjavaserial)

The code is working in real systems since 2014 on Windows and Linux (Ubuntu, Debian, Raspbian).

Volunteers are welcomed to document the classes and methods (javadoc).