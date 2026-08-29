# Initiative
Initiative is a local, developer-first and open source framework for monitoring, controlling, and interacting with all kinds of devices and systems through one unified interface.

Here's an AI written README because I'm really tired, I promise I mostly wrote this all myself though (and that short description above, cool right?)
--

Initiative is a fully local home automation framework designed to make it easy to connect, control, and build your own smart devices.

An Initiative network is made up of a central Core, any number of Nodes, and External Devices.

## Initiative Core

The Initiative Core is the center of an Initiative network. It keeps track of connected devices, handles actions, and provides the API and web interface used to control them.

The Core is written in Java and is intended to run continuously on a computer such as a Raspberry Pi or home server.

## Initiative Nodes

Initiative Nodes are devices that communicate directly with the Core. They can expose their capabilities to the Core and receive commands from it.

The Initiative Node software is written in C and uses Mongoose for networking, allowing it to run on lightweight devices such as ESP32s and Raspberry Pis as well as regular Linux systems.

Nodes are intended for custom hardware built specifically to integrate with Initiative.

## External Devices

External Devices are devices that don't run Initiative Node software themselves.

Instead, the Core communicates with them through scripts. These scripts define what the device can do and translate Initiative actions into whatever protocol or API the device already understands.

This allows existing smart-home hardware to be integrated into Initiative without modifying the device itself.
