# Spider Grills Venom for Hubitat Elevation

Hubitat Elevation Parent App and Child Driver for **Spider Grills Venom** grill/smoker controllers.

Ported from [brannonjames/spider-venom-home-assistant](https://github.com/brannonjames/spider-venom-home-assistant) to native Hubitat Groovy.

This integration connects Hubitat to your Spider Grills Venom controller(s) through the cloud-backed AWS IoT Device Shadow used by the Spider Grills mobile app. It discovers devices using a one-time Spider Grills email/password login, then immediately discards your password and access token—ongoing reads and writes use temporary AWS Cognito credentials signed with AWS Signature Version 4 (SigV4).

## Features

- **One-time account discovery** (email/password login, never stored after discovery) plus optional manual Thing Name entry.
- **Multi-device support**: Manage one or more Spider Grills Venom controllers as individual Hubitat child devices.
- **Full telemetry**: Current temperature, target temperature, min/max limits, probe 1 & probe 2 temperatures and targets, heat intensity, heat start time, high/low temp notification limits and alerts, lid/door open state, Wi-Fi SSID/BSSID/RSSI, firmware version, and error codes.
- **Full control**: Power (`on`/`off`), target temperature (`150–550 °F`), probe 1 & 2 target temperatures (`0–550 °F`), engaged, paused, and high-temperature mode—with 60-second optimistic state handling.
- **Standard Hubitat capabilities** for Dashboards, Rule Machine, and voice assistants: `Switch`, `TemperatureMeasurement`, `ThermostatHeatingSetpoint`, `ContactSensor` (lid/door), `SignalStrength`, and `Refresh`.

## Installation

### Hubitat Package Manager (HPM)

1. Open **Hubitat Package Manager** on your hub.
2. Select **Install** → **From a URL** and paste the raw `packageManifest.json` URL for this repository.

### Manual Installation

1. In Hubitat, go to **Apps Code** → **New App**, paste the contents of `apps/spider-venom-manager.groovy`, and click **Save**.
2. Go to **Drivers Code** → **New Driver**, paste the contents of `drivers/spider-venom-driver.groovy`, and click **Save**.
3. Go to **Apps** → **Add User App** → **Spider Grills Venom Manager**.

## Setup

1. Open **Spider Grills Venom Manager** in your Hubitat Apps list.
2. Tap **Discover Venom Devices via Spider Grills Account**, enter your Spider Grills email and password, and click **Sign In & Discover**.
   - Your password and access token are cleared immediately after discovery.
   - *(Apple Sign-In is not supported by the account discovery endpoint; if you already know your AWS IoT Thing Name, you can use the **Advanced: Add Thing Name Manually** page instead.)*
3. Return to the main page, select the discovered Venom controller(s), choose your preferred polling interval (default: 30 seconds), and click **Done**.

## Capabilities, Attributes & Commands

### Capabilities
| Capability | Maps To |
| --- | --- |
| `Switch` | Venom Power (`pwrOn`) |
| `TemperatureMeasurement` | Current Grill Temperature (`mainTemp`) |
| `ThermostatHeatingSetpoint` | Target Temperature (`heat.t2.trgt`) |
| `ContactSensor` | Door / Lid State (`doorOpn`: `open` / `closed`) |
| `SignalStrength` | Wi-Fi Signal Strength (`RSSI` in dBm) |
| `Refresh` | Manual AWS IoT shadow poll |

### Custom Attributes
- **Temperatures & Heat**: `targetTemperature`, `targetMin`, `targetMax`, `heatIntensity`, `heatStartTime`, `highTempLimit`, `lowTempLimit`
- **Probes**: `probe1Temp`, `probe1Target`, `probe2Temp`, `probe2Target`
- **Flags & Alerts**: `engaged`, `paused`, `heating`, `fahrenheitMode`, `highTempMode`, `highTempNotification`, `lowTempNotification`
- **Device & Network**: `model`, `firmwareVersion`, `macAddress`, `ssid`, `bssid`, `updateFlag`, `triggerFlag`, `errors`, `lastUpdate`

### Commands
- `on()` / `off()` — Turn Venom power on or off
- `setHeatingSetpoint(temperature)` / `setTargetTemperature(temperature)` — Set grill target temperature (`150–550 °F`)
- `setProbe1Target(temperature)` / `setProbe2Target(temperature)` — Set probe target temperature (`0–550 °F`)
- `engage()` / `disengage()` / `setEngaged("true" | "false")` — Toggle engaged state
- `pause()` / `resume()` / `setPaused("true" | "false")` — Toggle paused state
- `enableHighTempMode()` / `disableHighTempMode()` / `setHighTempMode("true" | "false")` — Toggle high-temperature mode
- `refresh()` — Poll the AWS IoT Device Shadow immediately

## Notes

- Matches the upstream Home Assistant integration: timer controls and vent-position attributes are not exposed because the observed AWS IoT shadow state does not include them.

## License

MIT License. See [LICENSE](LICENSE).
