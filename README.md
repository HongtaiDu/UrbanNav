# UrbanNav

## Overview

This project implements an indoor navigation system using Bluetooth Low Energy (BLE) beacons to guide users through buildings where GPS and cellular signals are weak or unavailable. BLE beacons broadcast signals that a companion mobile app uses to estimate the user's position and provide real-time wayfinding on a 2D floor plan.

## How It Works

BLE beacons are placed at known locations throughout a building. Rather than establishing persistent GATT connections, the system primarily uses **GAP advertising mode** — beacons continuously broadcast their identity, and phones passively scan for these signals. This architecture scales to an unlimited number of simultaneous users without taxing beacon hardware.

The mobile app estimates distance from each beacon using **RSSI (Received Signal Strength Indicator)** values and renders the user's approximate position on an indoor map.

## Project Context

This is an academic project at **New York University (NYU)**. Future development goals include multi-beacon trilateration for improved positioning accuracy, sensor fusion, and support for multiple floors.

## Contact

- **Name:** [Hongtai Du] & [Christopher Fonseca]
- **Email:** [hd2609@nyu.edu] & [cjf8329@nyu.edu]

---

*Last Updated: February 2026*
