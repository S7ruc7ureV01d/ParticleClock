# Paho MQTT loads its network modules via ServiceLoader and its log
# messages via a ResourceBundle, both by reflection.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**
