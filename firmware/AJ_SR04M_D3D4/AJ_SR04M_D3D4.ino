/*
 * AJ-SR04M / JSN-SR04T on an Arduino Nano, HC-08 on D2/D3.
 *
 * Serial.println() only ever leaves the chip on pins 0 and 1 - that is the
 * hardware UART and it cannot be moved. With the module on D2/D3 the readings
 * were printed to a wire nobody was listening to, which is why the app could
 * connect, subscribe, and receive nothing.
 *
 * SoftwareSerial bit-bangs a second port on any two pins. Now there are two
 * separate channels: USB for the Serial Monitor, D2/D3 for the module. No more
 * unplugging anything to flash.
 */

#include <SoftwareSerial.h>

// RX=D2, TX=D3. The module RX goes to D3.
SoftwareSerial ble(2, 3);

#define echoPin A1
#define trigPin A2

#define ECHO_TIMEOUT_US 30000UL
#define CYCLE_MS 50

long duration;
float distance;

void setup() {
  pinMode(trigPin, OUTPUT);
  pinMode(echoPin, INPUT);
  digitalWrite(trigPin, LOW);

  Serial.begin(115200);   // USB, for you
  // 9600 on purpose: SoftwareSerial is bit-banged and gets unreliable above it.
  ble.begin(9600);        // the HC-08 - must match the module's own baud
}

void loop() {
  digitalWrite(trigPin, LOW);
  delayMicroseconds(2);
  digitalWrite(trigPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(trigPin, LOW);

  duration = pulseIn(echoPin, HIGH, ECHO_TIMEOUT_US);
  distance = (duration == 0) ? -1.0 : duration / 58.0;

  int cm = (duration == 0) ? -1 : (int)(distance + 0.5);

  // to the module - a bare integer and a newline, nothing else
  ble.println(cm);

  // to the USB monitor - free to be verbose, it goes nowhere near the module
  Serial.print("cm=");
  Serial.println(cm);

  delay(CYCLE_MS);
}
