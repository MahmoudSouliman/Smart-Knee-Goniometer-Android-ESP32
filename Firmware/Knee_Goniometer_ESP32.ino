/*
 * Developed by: Eng. Mahmoud Souliman
 * Project: Smart Knee Goniometer (ESP32 + Dual MPU6050)
 */

#include "Wire.h"
#include <MPU6050_light.h>
#include "BluetoothSerial.h"

BluetoothSerial SerialBT;
MPU6050 thighSensor(Wire);
MPU6050 shinSensor(Wire);

unsigned long lastSendTime = 0;
const int sendInterval = 80; 

void setup() {
  Serial.begin(115200);
  SerialBT.begin("4MED_Knee_Goniometer");

  Wire.begin(21, 22);
  Wire.setClock(100000);

  thighSensor.begin();
  shinSensor.setAddress(0x69); // Ensure AD0 is connected to 3.3V
  shinSensor.begin();

  delay(1000);
  thighSensor.calcOffsets();
  shinSensor.calcOffsets();
}

void loop() {
  thighSensor.update();
  shinSensor.update();

  if (SerialBT.available()) {
    if (SerialBT.read() == 'z') {
      thighSensor.calcOffsets();
      shinSensor.calcOffsets();
    }
  }

  if (millis() - lastSendTime > sendInterval) {
    float kneeAngle = abs(thighSensor.getAngleX() - shinSensor.getAngleX());
    if (SerialBT.hasClient()) {
       SerialBT.println(kneeAngle);
    }
    lastSendTime = millis();
  }
}
