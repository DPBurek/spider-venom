/**
 *  Spider Grills Venom Driver
 *
 *  Hubitat Elevation Child Driver for Spider Grills Venom / Huntsman controllers
 *  via AWS IoT MQTT-over-WebSocket (interfaces.webSocket + native MQTT 3.1.1 framing).
 *
 *  Ported from brannonjames/spider-venom-home-assistant (Python / Home Assistant):
 *  https://github.com/brannonjames/spider-venom-home-assistant
 *
 *  Licensed under the MIT License.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import hubitat.helper.HexUtils
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Field static final String DRIVER_VERSION = "1.1.1"
@Field static final Integer MIN_TARGET_TEMPERATURE = 150
@Field static final Integer DEFAULT_MAX_TARGET_TEMPERATURE = 700
@Field static final Long OPTIMISTIC_TTL_MS = 60000L // 60 seconds

// In-memory per-device MQTT state (avoids database churn in Hubitat state)
@Field static final Map<String, byte[]> RX_BUFFERS = new ConcurrentHashMap()
@Field static final Map<String, Map> CONN_STATE = new ConcurrentHashMap()
@Field static final Map<String, Map> PENDING_UPDATES = new ConcurrentHashMap()

metadata {
    definition(
        name: "Spider Grills Venom",
        namespace: "dpburek",
        author: "DPBurek",
        importUrl: "https://raw.githubusercontent.com/DPBurek/spider-venom/master/drivers/spider-venom-driver.groovy"
    ) {
        capability "Initialize"
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "Switch"
        capability "TemperatureMeasurement"
        capability "ThermostatHeatingSetpoint"
        capability "ContactSensor"
        capability "SignalStrength"

        // Connection status
        attribute "connectionStatus", "enum", ["connected", "connecting", "disconnected"]

        // Temperature & Heat Sensors
        attribute "targetTemperature", "number"
        attribute "targetMin", "number"
        attribute "targetMax", "number"
        attribute "heatIntensity", "number"
        attribute "heatStartTime", "string"
        attribute "heatMode", "string"
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

        // Connection & Control Commands
        command "connect"
        command "disconnect"
        command "setTargetTemperature", [[name: "temperature*", type: "NUMBER", description: "Target temperature (150-550/700 °F)"]]
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
        input name: "autoReconnect", type: "bool", title: "Automatically reconnect MQTT WebSocket if disconnected", defaultValue: true
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
    runIn(2, "initialize")
}

void updated() {
    log.info "${device.displayName}: Settings updated"
    if (logEnable) {
        runIn(1800, "disableDebugLogging")
    }
    initialize()
}

void uninstalled() {
    disconnect()
    String dni = device.deviceNetworkId
    RX_BUFFERS.remove(dni)
    CONN_STATE.remove(dni)
    PENDING_UPDATES.remove(dni)
}

void initialize() {
    unschedule("reconnectMqtt")
    unschedule("mqttKeepalive")
    connect()
}

void disableDebugLogging() {
    log.info "${device.displayName}: Debug logging auto-disabled."
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ============================================================================
// MQTT-over-WebSocket Connection Management
// ============================================================================

void connect() {
    connectMqtt(false)
}

void disconnect() {
    unschedule("reconnectMqtt")
    unschedule("mqttKeepalive")
    Map cs = getConnState()
    cs.manualDisconnect = true
    cs.connected = false
    cs.subscribed = false
    RX_BUFFERS.remove(device.deviceNetworkId)
    try {
        // Send MQTT DISCONNECT (0xE0 0x00) cleanly if possible
        interfaces.webSocket.sendMessage("E000")
    } catch (ignored) {}
    try {
        interfaces.webSocket.close()
    } catch (ignored) {}
    emitEvent("connectionStatus", "disconnected", null, "MQTT WebSocket disconnected")
}

private void connectMqtt(boolean forceRefreshCreds = false) {
    String thingName = resolveThingName()
    if (!thingName) {
        log.error "${device.displayName}: Missing thingName data value; cannot connect."
        return
    }

    Map cs = getConnState()
    cs.manualDisconnect = false
    cs.connected = false
    cs.subscribed = false
    RX_BUFFERS.remove(device.deviceNetworkId)

    try {
        interfaces.webSocket.close()
    } catch (ignored) {}

    String wsUrl
    try {
        wsUrl = parent.getPresignedMqttUrl(forceRefreshCreds)
    } catch (Exception e) {
        log.error "${device.displayName}: Failed to obtain AWS IoT presigned URL: ${e.message}"
        emitEvent("connectionStatus", "disconnected", null, null)
        scheduleReconnect()
        return
    }

    emitEvent("connectionStatus", "connecting", null, null)
    logDebug "Connecting to AWS IoT MQTT WebSocket..."

    try {
        interfaces.webSocket.connect([
            byteInterface: true,
            pingInterval : 30,
            headers      : ["Sec-WebSocket-Protocol": "mqtt"]
        ], wsUrl)
    } catch (Exception e) {
        log.error "${device.displayName}: WebSocket connect failed: ${e.message}"
        emitEvent("connectionStatus", "disconnected", null, null)
        scheduleReconnect()
    }
}

void reconnectMqtt() {
    Map cs = getConnState()
    if (cs.manualDisconnect) {
        return
    }
    logDebug "Attempting MQTT WebSocket reconnect..."
    connectMqtt(true)
}

void mqttKeepalive() {
    Map cs = getConnState()
    if (!cs.connected) {
        return
    }
    try {
        logDebug "Sending MQTT PINGREQ"
        interfaces.webSocket.sendMessage("C000")
        runIn(45, "mqttKeepalive")
    } catch (Exception e) {
        log.warn "${device.displayName}: MQTT keepalive failed: ${e.message}"
        cs.connected = false
        cs.subscribed = false
        scheduleReconnect()
    }
}

void webSocketStatus(String status) {
    logDebug "WebSocket status: ${status}"
    Map cs = getConnState()

    if (status == "status: open") {
        cs.reconnectAttempts = 0
        // AWS IoT policy requires a dashed UUID as the MQTT client ID
        String clientId = UUID.randomUUID().toString()
        logDebug "WebSocket open; sending MQTT CONNECT (clientId=${clientId})"
        sendMqttBytes(buildConnectPacket(clientId, 60))
    } else if (status?.startsWith("status: closing") || status?.startsWith("failure:")) {
        boolean wasConnected = cs.connected as Boolean
        cs.connected = false
        cs.subscribed = false
        unschedule("mqttKeepalive")
        RX_BUFFERS.remove(device.deviceNetworkId)
        emitEvent("connectionStatus", "disconnected", null, wasConnected ? "MQTT WebSocket closed (${status})" : null)
        if (!cs.manualDisconnect && (settings.autoReconnect != false)) {
            scheduleReconnect()
        }
    }
}

private void scheduleReconnect() {
    if (settings.autoReconnect == false) {
        return
    }
    Map cs = getConnState()
    int attempts = ((cs.reconnectAttempts ?: 0) as int) + 1
    cs.reconnectAttempts = attempts
    int delaySec = Math.min(60, attempts * 10)
    logDebug "Scheduling MQTT reconnect in ${delaySec}s (attempt ${attempts})"
    runIn(delaySec, "reconnectMqtt")
}

// ============================================================================
// Incoming WebSocket / MQTT Packet Parser
// ============================================================================

void parse(String description) {
    if (!description) {
        return
    }

    byte[] incoming
    try {
        incoming = HexUtils.hexStringToByteArray(description)
    } catch (Exception e) {
        logDebug "Non-hex WebSocket message ignored: ${description}"
        return
    }

    if (!incoming || incoming.length == 0) {
        return
    }

    String dni = device.deviceNetworkId
    byte[] existing = RX_BUFFERS.get(dni)
    byte[] buf = (existing && existing.length > 0) ? concatBytes(existing, incoming) : incoming

    int offset = 0
    while (offset < buf.length) {
        if (buf.length - offset < 2) {
            break // Need more bytes for header + remaining length
        }

        // Decode MQTT variable-byte Remaining Length
        int idx = offset + 1
        int multiplier = 1
        int remLen = 0
        boolean completeVarInt = false
        while (idx < buf.length) {
            int encodedByte = buf[idx] & 0xFF
            idx++
            remLen += (encodedByte & 127) * multiplier
            if ((encodedByte & 128) == 0) {
                completeVarInt = true
                break
            }
            multiplier *= 128
            if (multiplier > 128 * 128 * 128) {
                log.warn "${device.displayName}: Malformed MQTT remaining length; resetting buffer"
                RX_BUFFERS.remove(dni)
                return
            }
        }

        if (!completeVarInt) {
            break
        }

        int headerLen = idx - offset
        int totalPacketLen = headerLen + remLen
        if (buf.length - offset < totalPacketLen) {
            break // Wait for remaining WebSocket frames of this MQTT packet
        }

        byte[] pkt = sliceBytes(buf, offset, totalPacketLen)
        offset += totalPacketLen

        try {
            handleMqttPacket(pkt, headerLen)
        } catch (Exception e) {
            log.error "${device.displayName}: Error handling MQTT packet: ${e.message}"
        }
    }

    if (offset < buf.length) {
        RX_BUFFERS.put(dni, sliceBytes(buf, offset, buf.length - offset))
    } else {
        RX_BUFFERS.remove(dni)
    }
}

private void handleMqttPacket(byte[] pkt, int bodyStart) {
    int firstByte = pkt[0] & 0xFF
    int pktType = (firstByte >> 4) & 0x0F
    int flags = firstByte & 0x0F
    Map cs = getConnState()
    String thingName = resolveThingName()

    switch (pktType) {
        case 2: // CONNACK
            int returnCode = (pkt.length >= 4) ? (pkt[3] & 0xFF) : -1
            if (returnCode == 0) {
                logDebug "MQTT CONNACK accepted; subscribing to shadow topics..."
                cs.connected = true
                emitEvent("connectionStatus", "connected", null, "Connected to AWS IoT MQTT")
                runIn(45, "mqttKeepalive")
                List<String> topics = [
                    "\$aws/things/${thingName}/shadow/get/accepted",
                    "\$aws/things/${thingName}/shadow/get/rejected",
                    "\$aws/things/${thingName}/shadow/update/accepted",
                    "\$aws/things/${thingName}/shadow/update/rejected",
                    "\$aws/things/${thingName}/shadow/update/documents"
                ]
                sendMqttBytes(buildSubscribePacket(nextPacketId(), topics))
            } else {
                log.error "${device.displayName}: MQTT CONNACK refused (code=${returnCode})"
                cs.connected = false
                emitEvent("connectionStatus", "disconnected", null, null)
            }
            break

        case 9: // SUBACK
            logDebug "MQTT SUBACK received; requesting current shadow..."
            cs.subscribed = true
            Map pending = PENDING_UPDATES.remove(device.deviceNetworkId)
            if (pending) {
                publishShadowUpdate(thingName, pending)
            }
            publishShadowGet(thingName)
            break

        case 3: // PUBLISH
            int pos = bodyStart
            if (pos + 2 > pkt.length) {
                return
            }
            int topicLen = ((pkt[pos] & 0xFF) << 8) | (pkt[pos + 1] & 0xFF)
            pos += 2
            if (pos + topicLen > pkt.length) {
                return
            }
            String topic = new String(pkt, pos, topicLen, "UTF-8")
            pos += topicLen

            int qos = (flags >> 1) & 0x03
            if (qos > 0) {
                if (pos + 2 > pkt.length) {
                    return
                }
                int pidHi = pkt[pos] & 0xFF
                int pidLo = pkt[pos + 1] & 0xFF
                pos += 2
                if (qos == 1) {
                    // Send PUBACK
                    sendMqttBytes([(byte) 0x40, (byte) 0x02, (byte) pidHi, (byte) pidLo] as byte[])
                }
            }

            String payloadStr = (pos < pkt.length) ? new String(pkt, pos, pkt.length - pos, "UTF-8") : ""
            handleShadowMessage(topic, payloadStr)
            break

        case 4: // PUBACK
            logDebug "MQTT PUBACK received"
            break

        case 13: // PINGRESP
            logDebug "MQTT PINGRESP received"
            break

        default:
            logDebug "Unhandled MQTT packet type ${pktType} (len=${pkt.length})"
            break
    }
}

private void handleShadowMessage(String topic, String payloadStr) {
    if (!payloadStr) {
        return
    }
    def parsed
    try {
        parsed = new JsonSlurper().parseText(payloadStr)
    } catch (Exception e) {
        log.warn "${device.displayName}: Could not parse shadow JSON on ${topic}: ${e.message}"
        return
    }
    if (!(parsed instanceof Map)) {
        return
    }
    Map doc = parsed as Map
    logDebug "MQTT message on ${topic}"

    if (topic.endsWith("/rejected")) {
        log.warn "${device.displayName}: AWS IoT shadow rejected (${topic}): code=${doc.code}, message=${doc.message}"
        return
    }

    if (topic.endsWith("/shadow/get/accepted")) {
        applyShadow(doc)
    } else if (topic.endsWith("/shadow/update/documents")) {
        if (doc.current instanceof Map) {
            applyShadow(doc.current as Map)
        }
    } else if (topic.endsWith("/shadow/update/accepted")) {
        if (doc.state?.reported instanceof Map) {
            applyShadow(doc)
        }
    }
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

    Map cs = getConnState()
    if (cs.connected && cs.subscribed) {
        publishShadowGet(thingName)
    } else {
        connectMqtt(false)
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
    int maxAllowed = currentMaxTargetTemp()
    if (temp < MIN_TARGET_TEMPERATURE || temp > maxAllowed) {
        log.warn "${device.displayName}: Target temperature must be between ${MIN_TARGET_TEMPERATURE} and ${maxAllowed} °F (got ${temp})."
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
    int maxAllowed = currentMaxTargetTemp()
    if (temp < 0 || temp > maxAllowed) {
        log.warn "${device.displayName}: Probe 1 target must be between 0 and ${maxAllowed} °F (got ${temp})."
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
    int maxAllowed = currentMaxTargetTemp()
    if (temp < 0 || temp > maxAllowed) {
        log.warn "${device.displayName}: Probe 2 target must be between 0 and ${maxAllowed} °F (got ${temp})."
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
// Shadow State Processing
// ============================================================================

void applyShadow(Map shadow) {
    Map reported = (shadow?.state?.reported instanceof Map) ? (shadow.state.reported as Map) : null
    if (reported == null) {
        logDebug "Shadow payload did not contain state.reported"
        return
    }

    logDebug "Applying shadow reported state (model=${reported.model}, mainTemp=${reported.mainTemp})"

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
        emitEvent("errors", errStr, null, null)
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

    def heatMode = valueAtPath(reported, ["heat", "t2", "mode"])
    if (heatMode != null) {
        emitEvent("heatMode", heatMode.toString(), null, null)
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
// MQTT 3.1.1 Packet Builders & Publish Helpers
// ============================================================================

private void publishShadowGet(String thingName) {
    String topic = "\$aws/things/${thingName}/shadow/get"
    String token = UUID.randomUUID().toString().replace("-", "")
    String payload = JsonOutput.toJson([clientToken: token])
    logDebug "Publishing shadow GET to ${topic}"
    sendMqttBytes(buildPublishPacket(topic, payload, nextPacketId()))
}

private void publishShadowUpdate(String thingName, Map desired) {
    String topic = "\$aws/things/${thingName}/shadow/update"
    String token = UUID.randomUUID().toString().replace("-", "")
    String payload = JsonOutput.toJson([state: [desired: desired], clientToken: token])
    logDebug "Publishing shadow UPDATE to ${topic}: ${payload}"
    sendMqttBytes(buildPublishPacket(topic, payload, nextPacketId()))
}

private byte[] buildConnectPacket(String clientId, int keepAliveSec) {
    List<Byte> body = []
    body.addAll(encodeMqttString("MQTT"))
    body.add((byte) 0x04) // Protocol Level 4 (MQTT 3.1.1)
    body.add((byte) 0x02) // Clean Session = 1
    body.add(((keepAliveSec >> 8) & 0xFF) as byte)
    body.add((keepAliveSec & 0xFF) as byte)
    body.addAll(encodeMqttString(clientId))

    List<Byte> pkt = [(byte) 0x10]
    pkt.addAll(encodeVarInt(body.size()))
    pkt.addAll(body)
    return toByteArray(pkt)
}

private byte[] buildSubscribePacket(int packetId, List<String> topics) {
    List<Byte> body = [
        ((packetId >> 8) & 0xFF) as byte,
        (packetId & 0xFF) as byte
    ]
    topics.each { String topic ->
        body.addAll(encodeMqttString(topic))
        body.add((byte) 0x01) // QoS 1
    }

    List<Byte> pkt = [(byte) 0x82]
    pkt.addAll(encodeVarInt(body.size()))
    pkt.addAll(body)
    return toByteArray(pkt)
}

private byte[] buildPublishPacket(String topic, String payload, Integer packetId) {
    List<Byte> body = []
    body.addAll(encodeMqttString(topic))
    byte header
    if (packetId != null) {
        header = (byte) 0x32 // QoS 1
        body.add(((packetId >> 8) & 0xFF) as byte)
        body.add((packetId & 0xFF) as byte)
    } else {
        header = (byte) 0x30 // QoS 0
    }
    byte[] payloadBytes = payload.getBytes("UTF-8")
    for (int i = 0; i < payloadBytes.length; i++) {
        body.add(payloadBytes[i])
    }

    List<Byte> pkt = [header]
    pkt.addAll(encodeVarInt(body.size()))
    pkt.addAll(body)
    return toByteArray(pkt)
}

private List<Byte> encodeMqttString(String s) {
    byte[] utf8 = s.getBytes("UTF-8")
    int len = utf8.length
    List<Byte> out = [((len >> 8) & 0xFF) as byte, (len & 0xFF) as byte]
    for (int i = 0; i < len; i++) {
        out.add(utf8[i])
    }
    return out
}

private List<Byte> encodeVarInt(int value) {
    List<Byte> out = []
    int n = value
    while (true) {
        int b = n % 128
        n = n.intdiv(128) as int
        if (n > 0) {
            b = b | 0x80
        }
        out.add((b & 0xFF) as byte)
        if (n == 0) {
            break
        }
    }
    return out
}

private byte[] toByteArray(List<Byte> list) {
    byte[] arr = new byte[list.size()]
    for (int i = 0; i < list.size(); i++) {
        arr[i] = list.get(i).byteValue()
    }
    return arr
}

private byte[] concatBytes(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length]
    for (int i = 0; i < a.length; i++) {
        out[i] = a[i]
    }
    for (int i = 0; i < b.length; i++) {
        out[a.length + i] = b[i]
    }
    return out
}

private byte[] sliceBytes(byte[] src, int start, int len) {
    byte[] out = new byte[len]
    for (int i = 0; i < len; i++) {
        out[i] = src[start + i]
    }
    return out
}

private void sendMqttBytes(byte[] data) {
    String hex = HexUtils.byteArrayToHexString(data)
    interfaces.webSocket.sendMessage(hex)
}

private int nextPacketId() {
    Map cs = getConnState()
    int pid = ((cs.packetId ?: 0) as int) + 1
    if (pid > 65535) {
        pid = 1
    }
    cs.packetId = pid
    return pid
}

private Map getConnState() {
    String dni = device.deviceNetworkId
    Map cs = CONN_STATE.get(dni)
    if (cs == null) {
        cs = new ConcurrentHashMap()
        CONN_STATE.put(dni, cs)
    }
    return cs
}

// ============================================================================
// Control & Optimistic State Helpers
// ============================================================================

private void sendDesiredControl(Map desired, String optimisticKey, def optimisticValue, Closure onSuccess) {
    String thingName = resolveThingName()
    if (!thingName) {
        log.error "${device.displayName}: Missing thingName data value; cannot send control command."
        return
    }

    setOptimistic(optimisticKey, optimisticValue)
    onSuccess?.call()

    Map cs = getConnState()
    if (cs.connected && cs.subscribed) {
        try {
            publishShadowUpdate(thingName, desired)
            runIn(3, "refresh")
        } catch (Exception e) {
            log.error "${device.displayName}: Failed to publish Venom control (${optimisticKey}): ${e.message}"
        }
    } else {
        PENDING_UPDATES.put(device.deviceNetworkId, desired)
        connectMqtt(false)
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

private int currentMaxTargetTemp() {
    def reportedMax = device.currentValue("targetMax")
    if (reportedMax instanceof Number && (reportedMax as int) >= 550) {
        return reportedMax as int
    }
    return DEFAULT_MAX_TARGET_TEMPERATURE
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
