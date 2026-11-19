/**
 *  Spider Grills Venom Driver
 *
 *  Hubitat Elevation Child Driver for Spider Grills Venom controllers
 *  via the Spider Grills Venom Manager parent app (AWS IoT Device Shadow).
 *
 *  Ported from brannonjames/spider-venom-home-assistant (Python / Home Assistant):
 *  https://github.com/brannonjames/spider-venom-home-assistant
 *
 *  Licensed under the MIT License.
 */

import groovy.transform.Field
import java.text.SimpleDateFormat
import java.util.TimeZone

@Field static final String DRIVER_VERSION = "1.0.0"
@Field static final Integer MIN_TARGET_TEMPERATURE = 150
@Field static final Integer MAX_TARGET_TEMPERATURE = 550
@Field static final Long OPTIMISTIC_TTL_MS = 60000L // 60 seconds (matches HA integration)

metadata {
    definition(
        name: "Spider Grills Venom",
        namespace: "dpburek",
        author: "DPBurek",
        importUrl: "https://raw.githubusercontent.com/DPBurek/spider-venom/master/drivers/spider-venom-driver.groovy"
    ) {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "Switch"
        capability "TemperatureMeasurement"
        capability "ThermostatHeatingSetpoint"
        capability "ContactSensor"
        capability "SignalStrength"

        // Temperature & Heat Sensors
        attribute "targetTemperature", "number"
        attribute "targetMin", "number"
        attribute "targetMax", "number"
        attribute "heatIntensity", "number"
        attribute "heatStartTime", "string"
        attribute "highTempLimit", "number"
        attribute "lowTempLimit", "number"

        // Probe Sensors
        attribute "probe1Temp", "number"
        attribute "probe1Target", "number"
        attribute "probe2Temp", "number"
        attribute "probe2Target", "number"

        // Status / Binary Sensors
        attribute "engaged", "enum", ["true", "false"]
        attribute "paused", "enum", ["true", "false"]
        attribute "heating", "enum", ["true", "false"]
        attribute "fahrenheitMode", "enum", ["true", "false"]
        attribute "highTempMode", "enum", ["true", "false"]
        attribute "highTempNotification", "enum", ["true", "false"]
        attribute "lowTempNotification", "enum", ["true", "false"]

        // Device & Network Info
        attribute "model", "string"
        attribute "firmwareVersion", "string"
        attribute "macAddress", "string"
        attribute "ssid", "string"
        attribute "bssid", "string"
        attribute "updateFlag", "string"
        attribute "triggerFlag", "string"
        attribute "errors", "string"
        attribute "lastUpdate", "string"

        // Controls
        command "setTargetTemperature", [[name: "temperature*", type: "NUMBER", description: "Target temperature (150-550 °F)"]]
        command "setProbe1Target", [[name: "temperature*", type: "NUMBER", description: "Probe 1 target temperature (0-550 °F)"]]
        command "setProbe2Target", [[name: "temperature*", type: "NUMBER", description: "Probe 2 target temperature (0-550 °F)"]]
        command "engage"
        command "disengage"
        command "setEngaged", [[name: "enabled*", type: "ENUM", constraints: ["true", "false"]]]
        command "pause"
        command "resume"
        command "setPaused", [[name: "enabled*", type: "ENUM", constraints: ["true", "false"]]]
        command "enableHighTempMode"
        command "disableHighTempMode"
        command "setHighTempMode", [[name: "enabled*", type: "ENUM", constraints: ["true", "false"]]]
    }

    preferences {
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging (auto-disables after 30 minutes)", defaultValue: false
    }
}

// ============================================================================
// Lifecycle
// ============================================================================

void installed() {
    log.info "${device.displayName}: Spider Grills Venom Driver v${DRIVER_VERSION} installed"
    state.optimistic = [:]
}

void updated() {
    log.info "${device.displayName}: Settings updated"
    if (logEnable) {
        runIn(1800, "disableDebugLogging")
    }
}

void disableDebugLogging() {
    log.info "${device.displayName}: Debug logging auto-disabled."
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ============================================================================
// Commands
// ============================================================================

void refresh() {
    String thingName = resolveThingName()
    if (!thingName) {
        log.error "${device.displayName}: Missing thingName data value; cannot refresh."
        return
    }

    try {
        Map shadow = parent.getShadow(thingName)
        if (shadow != null) {
            applyShadow(shadow)
        }
    } catch (Exception e) {
        log.warn "${device.displayName}: Refresh failed: ${e.message}"
    }
}

void on() {
    sendDesiredControl([pwrOn: true], "power", true) {
        emitEvent("switch", "on", null, "Power turned on")
    }
}

void off() {
    sendDesiredControl([pwrOn: false], "power", false) {
        emitEvent("switch", "off", null, "Power turned off")
    }
}

void setHeatingSetpoint(BigDecimal temperature) {
    setTargetTemperature(temperature)
}

void setTargetTemperature(BigDecimal temperature) {
    if (temperature == null) {
        log.warn "${device.displayName}: Target temperature cannot be null."
        return
    }
    int temp = Math.round(temperature.toDouble()) as int
    if (temp < MIN_TARGET_TEMPERATURE || temp > MAX_TARGET_TEMPERATURE) {
        log.warn "${device.displayName}: Target temperature must be between ${MIN_TARGET_TEMPERATURE} and ${MAX_TARGET_TEMPERATURE} °F (got ${temp})."
        return
    }
    String unit = currentTempUnit()
    sendDesiredControl([heat: [t2: [trgt: temp]]], "targetTemp", temp) {
        emitEvent("heatingSetpoint", temp, unit, "Target temperature set to ${temp}${unit}")
        emitEvent("targetTemperature", temp, unit, null)
    }
}

void setProbe1Target(BigDecimal temperature) {
    if (temperature == null) {
        log.warn "${device.displayName}: Probe 1 target temperature cannot be null."
        return
    }
    int temp = Math.round(temperature.toDouble()) as int
    if (temp < 0 || temp > MAX_TARGET_TEMPERATURE) {
        log.warn "${device.displayName}: Probe 1 target must be between 0 and ${MAX_TARGET_TEMPERATURE} °F (got ${temp})."
        return
    }
    String unit = currentTempUnit()
    sendDesiredControl([probes: [p1: [trgt: temp]]], "probe1Target", temp) {
        emitEvent("probe1Target", temp, unit, "Probe 1 target set to ${temp}${unit}")
    }
}

void setProbe2Target(BigDecimal temperature) {
    if (temperature == null) {
        log.warn "${device.displayName}: Probe 2 target temperature cannot be null."
        return
    }
    int temp = Math.round(temperature.toDouble()) as int
    if (temp < 0 || temp > MAX_TARGET_TEMPERATURE) {
        log.warn "${device.displayName}: Probe 2 target must be between 0 and ${MAX_TARGET_TEMPERATURE} °F (got ${temp})."
        return
    }
    String unit = currentTempUnit()
    sendDesiredControl([probes: [p2: [trgt: temp]]], "probe2Target", temp) {
        emitEvent("probe2Target", temp, unit, "Probe 2 target set to ${temp}${unit}")
    }
}

void engage() {
    setEngaged("true")
}

void disengage() {
    setEngaged("false")
}

void setEngaged(String enabled) {
    boolean val = (enabled?.toLowerCase() == "true")
    sendDesiredControl([engaged: val], "engaged", val) {
        emitEvent("engaged", val.toString(), null, "Engaged set to ${val}")
    }
}

void pause() {
    setPaused("true")
}

void resume() {
    setPaused("false")
}

void setPaused(String enabled) {
    boolean val = (enabled?.toLowerCase() == "true")
    sendDesiredControl([paused: val], "paused", val) {
        emitEvent("paused", val.toString(), null, "Paused set to ${val}")
    }
}

void enableHighTempMode() {
    setHighTempMode("true")
}

void disableHighTempMode() {
    setHighTempMode("false")
}

void setHighTempMode(String enabled) {
    boolean val = (enabled?.toLowerCase() == "true")
    sendDesiredControl([heat: [t2: [highTemp_enable: val]]], "highTempMode", val) {
        emitEvent("highTempMode", val.toString(), null, "High temperature mode set to ${val}")
    }
}

// ============================================================================
// Shadow State Processing (Called by Parent App or refresh())
// ============================================================================

void applyShadow(Map shadow) {
    Map reported = (shadow?.state?.reported instanceof Map) ? (shadow.state.reported as Map) : null
    if (reported == null) {
        logDebug "Shadow payload did not contain state.reported"
        return
    }

    logDebug "Applying shadow reported state: ${reported}"

    // Determine temperature unit (default °F matching HA integration)
    def fahVal = valueAtPath(reported, ["fah"])
    String tempUnit = (fahVal != null && !(fahVal as Boolean)) ? "°C" : "°F"

    // Device & Network info
    def modelVal = valueAtPath(reported, ["model"])
    if (modelVal != null) {
        emitEvent("model", modelVal.toString(), null, null)
        device.updateDataValue("model", modelVal.toString())
    }

    def versVal = valueAtPath(reported, ["vers"])
    if (versVal != null) {
        emitEvent("firmwareVersion", versVal.toString(), null, null)
        device.updateDataValue("firmwareVersion", versVal.toString())
    }

    def macVal = valueAtPath(reported, ["mac"])
    if (macVal != null) {
        emitEvent("macAddress", macVal.toString(), null, null)
        device.updateDataValue("macAddress", macVal.toString())
    }

    def rssiVal = valueAtPath(reported, ["RSSI"])
    if (rssiVal != null) {
        emitEvent("rssi", rssiVal, "dBm", null)
    }

    def ssidVal = valueAtPath(reported, ["ssid"])
    if (ssidVal != null) {
        emitEvent("ssid", ssidVal.toString(), null, null)
    }

    def bssidVal = valueAtPath(reported, ["bssid"])
    if (bssidVal != null) {
        emitEvent("bssid", bssidVal.toString(), null, null)
    }

    def updateVal = valueAtPath(reported, ["update"])
    if (updateVal != null) {
        emitEvent("updateFlag", updateVal.toString(), null, null)
    }

    def triggerVal = valueAtPath(reported, ["trigger"])
    if (triggerVal != null) {
        emitEvent("triggerFlag", triggerVal.toString(), null, null)
    }

    if (reported.errors instanceof List) {
        String errStr = (reported.errors as List).collect { it.toString() }.join(",")
        emitEvent("errors", errStr, null, errStr ? "Errors reported: ${errStr}" : null)
    }

    // Temperatures & Heat telemetry
    def mainTemp = valueAtPath(reported, ["mainTemp"])
    if (mainTemp != null) {
        emitEvent("temperature", mainTemp, tempUnit, "Current temperature is ${mainTemp}${tempUnit}")
    }

    def targetTemp = resolveOptimistic("targetTemp", valueAtPath(reported, ["heat", "t2", "trgt"]))
    if (targetTemp != null) {
        emitEvent("heatingSetpoint", targetTemp, tempUnit, "Target temperature is ${targetTemp}${tempUnit}")
        emitEvent("targetTemperature", targetTemp, tempUnit, null)
    }

    def targetMin = valueAtPath(reported, ["heat", "t2", "min"])
    if (targetMin != null) {
        emitEvent("targetMin", targetMin, tempUnit, null)
    }

    def targetMax = valueAtPath(reported, ["heat", "t2", "max"])
    if (targetMax != null) {
        emitEvent("targetMax", targetMax, tempUnit, null)
    }

    def heatIntensity = valueAtPath(reported, ["heat", "t2", "intensity"])
    if (heatIntensity != null) {
        emitEvent("heatIntensity", heatIntensity, "%", null)
    }

    def startTimeVal = valueAtPath(reported, ["heat", "t2", "startTime"])
    if (startTimeVal instanceof Number && startTimeVal.doubleValue() > 0) {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        iso.setTimeZone(TimeZone.getTimeZone("UTC"))
        String ts = iso.format(new Date((startTimeVal.doubleValue() * 1000.0d) as Long))
        emitEvent("heatStartTime", ts, null, null)
    }

    def highLimit = valueAtPath(reported, ["heat", "t2", "high_limit"])
    if (highLimit != null) {
        emitEvent("highTempLimit", highLimit, tempUnit, null)
    }

    def lowLimit = valueAtPath(reported, ["heat", "t2", "low_limit"])
    if (lowLimit != null) {
        emitEvent("lowTempLimit", lowLimit, tempUnit, null)
    }

    // Probes
    def p1Temp = valueAtPath(reported, ["probes", "p1", "temp"])
    if (p1Temp != null) {
        emitEvent("probe1Temp", p1Temp, tempUnit, "Probe 1 temperature is ${p1Temp}${tempUnit}")
    }

    def p1Target = resolveOptimistic("probe1Target", valueAtPath(reported, ["probes", "p1", "trgt"]))
    if (p1Target != null) {
        emitEvent("probe1Target", p1Target, tempUnit, null)
    }

    def p2Temp = valueAtPath(reported, ["probes", "p2", "temp"])
    if (p2Temp != null) {
        emitEvent("probe2Temp", p2Temp, tempUnit, "Probe 2 temperature is ${p2Temp}${tempUnit}")
    }

    def p2Target = resolveOptimistic("probe2Target", valueAtPath(reported, ["probes", "p2", "trgt"]))
    if (p2Target != null) {
        emitEvent("probe2Target", p2Target, tempUnit, null)
    }

    // Binary / Switch States
    def pwrOn = resolveOptimistic("power", valueAtPath(reported, ["pwrOn"]))
    if (pwrOn != null) {
        String swVal = (pwrOn as Boolean) ? "on" : "off"
        emitEvent("switch", swVal, null, "Power is ${swVal}")
    }

    def engagedVal = resolveOptimistic("engaged", valueAtPath(reported, ["engaged"]))
    if (engagedVal != null) {
        emitEvent("engaged", (engagedVal as Boolean).toString(), null, null)
    }

    def pausedVal = resolveOptimistic("paused", valueAtPath(reported, ["paused"]))
    if (pausedVal != null) {
        emitEvent("paused", (pausedVal as Boolean).toString(), null, null)
    }

    def doorOpn = valueAtPath(reported, ["doorOpn"])
    if (doorOpn != null) {
        String contactVal = (doorOpn as Boolean) ? "open" : "closed"
        emitEvent("contact", contactVal, null, "Door is ${contactVal}")
    }

    def heatingVal = valueAtPath(reported, ["heat", "t2", "heating"])
    if (heatingVal != null) {
        emitEvent("heating", (heatingVal as Boolean).toString(), null, null)
    }

    if (fahVal != null) {
        emitEvent("fahrenheitMode", (fahVal as Boolean).toString(), null, null)
    }

    def highTempEnabled = resolveOptimistic("highTempMode", valueAtPath(reported, ["heat", "t2", "highTemp_enable"]))
    if (highTempEnabled != null) {
        emitEvent("highTempMode", (highTempEnabled as Boolean).toString(), null, null)
    }

    def highTempNotif = valueAtPath(reported, ["notifications", "high_temp"])
    if (highTempNotif != null) {
        emitEvent("highTempNotification", (highTempNotif as Boolean).toString(), null, null)
    }

    def lowTempNotif = valueAtPath(reported, ["notifications", "low_temp"])
    if (lowTempNotif != null) {
        emitEvent("lowTempNotification", (lowTempNotif as Boolean).toString(), null, null)
    }

    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
}

// ============================================================================
// Helpers
// ============================================================================

private void sendDesiredControl(Map desired, String optimisticKey, def optimisticValue, Closure onSuccess) {
    String thingName = resolveThingName()
    if (!thingName) {
        log.error "${device.displayName}: Missing thingName data value; cannot send control command."
        return
    }

    try {
        parent.updateDesiredShadow(thingName, desired)
        setOptimistic(optimisticKey, optimisticValue)
        onSuccess?.call()
        runIn(3, "refresh")
    } catch (Exception e) {
        log.error "${device.displayName}: Failed to update Venom control (${optimisticKey}): ${e.message}"
    }
}

private void setOptimistic(String key, def value) {
    Map opt = state.optimistic ?: [:]
    opt[key] = [value: value, until: now() + OPTIMISTIC_TTL_MS]
    state.optimistic = opt
}

private def resolveOptimistic(String key, def reportedValue) {
    Map opt = state.optimistic
    if (!opt || !opt.containsKey(key)) {
        return reportedValue
    }
    Map entry = opt[key] as Map
    long until = (entry.until ?: 0L) as Long
    def optVal = entry.value

    boolean matched = (reportedValue instanceof Number && optVal instanceof Number)
        ? ((reportedValue as BigDecimal) == (optVal as BigDecimal))
        : (reportedValue == optVal)

    if (matched || now() >= until) {
        opt.remove(key)
        state.optimistic = opt
        return reportedValue
    }
    return optVal
}

private def valueAtPath(Map data, List<String> path) {
    def current = data
    for (String key : path) {
        if (!(current instanceof Map)) {
            return null
        }
        current = current[key]
    }
    return current
}

private String resolveThingName() {
    String tn = device.getDataValue("thingName")
    if (!tn && device.deviceNetworkId?.startsWith("spider-venom-")) {
        tn = device.deviceNetworkId.replaceFirst(/^spider-venom-/, "")
    }
    return tn
}

private String currentTempUnit() {
    return (device.currentValue("fahrenheitMode") == "false") ? "°C" : "°F"
}

private void emitEvent(String name, def value, String unit, String desc) {
    def current = device.currentValue(name)
    boolean changed = (current?.toString() != value?.toString())
    Map evt = [name: name, value: value]
    if (unit) {
        evt.unit = unit
    }
    if (desc) {
        evt.descriptionText = "${device.displayName}: ${desc}"
    }
    sendEvent(evt)
    if (changed && desc && txtEnable) {
        log.info "${device.displayName}: ${desc}"
    }
}

private void logDebug(String msg) {
    if (logEnable) {
        log.debug "${device.displayName}: ${msg}"
    }
}
