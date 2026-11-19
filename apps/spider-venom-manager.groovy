/**
 *  Spider Grills Venom Manager
 *
 *  Hubitat Elevation Parent App for Spider Grills Venom controllers
 *  via the Spider Grills cloud discovery API and AWS Cognito + AWS IoT Device Shadow.
 *
 *  Ported from brannonjames/spider-venom-home-assistant (Python / Home Assistant):
 *  https://github.com/brannonjames/spider-venom-home-assistant
 *
 *  Licensed under the MIT License.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Field static final String APP_VERSION = "1.0.0"
@Field static final String CHILD_NAMESPACE = "dpburek"
@Field static final String CHILD_DRIVER_NAME = "Spider Grills Venom"

@Field static final String SPIDER_ACCOUNT_BASE_URL = "https://spidergrills.app"
@Field static final String DEFAULT_ENDPOINT = "a1gzggdqzynf8-ats.iot.us-east-2.amazonaws.com"
@Field static final String DEFAULT_IDENTITY_POOL_ID = "us-east-2:900c6051-7296-4d27-9295-a11f72797d14"
@Field static final String DEFAULT_REGION = "us-east-2"

@Field static final Long CREDENTIAL_REFRESH_BUFFER_MS = 300000L // 5 minutes

@Field static final Map POLL_INTERVAL_OPTIONS = [
    "0"  : "Manual only (no automatic polling)",
    "15" : "Every 15 seconds",
    "30" : "Every 30 seconds (Recommended)",
    "60" : "Every 1 minute",
    "300": "Every 5 minutes",
    "600": "Every 10 minutes",
    "900": "Every 15 minutes"
]

@Field static final Set<String> REDACTED_KEYS = [
    "accesskeyid",
    "accesstoken",
    "authorization",
    "idtoken",
    "password",
    "refreshtoken",
    "secretkey",
    "sessiontoken"
] as Set

definition(
    name: "Spider Grills Venom Manager",
    namespace: "dpburek",
    author: "DPBurek",
    description: "Connects Hubitat Elevation to Spider Grills Venom controllers via AWS IoT Device Shadow.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true,
    importUrl: "https://raw.githubusercontent.com/DPBurek/spider-venom/master/apps/spider-venom-manager.groovy"
)

preferences {
    page(name: "mainPage")
    page(name: "discoverPage")
    page(name: "manualAddPage")
}

// ============================================================================
// UI Pages
// ============================================================================

def mainPage() {
    dynamicPage(name: "mainPage", title: "Spider Grills Venom Manager v${APP_VERSION}", install: true, uninstall: true) {
        section("<b>Device Discovery</b>") {
            paragraph "Sign in once with your Spider Grills email and password to discover your Venom controller(s). " +
                      "Your password and access token are never saved after discovery—ongoing communication uses temporary AWS Cognito credentials."
            href(
                name: "toDiscoverPage",
                page: "discoverPage",
                title: "Discover Venom Devices via Spider Grills Account",
                description: state.discoveredDevices ? "${state.discoveredDevices.size()} device(s) discovered" : "Tap to sign in and discover devices"
            )
            href(
                name: "toManualAddPage",
                page: "manualAddPage",
                title: "Advanced: Add Thing Name Manually",
                description: "Optional fallback if you already know your AWS IoT Thing Name"
            )
        }

        if (state.discoveredDevices && !state.discoveredDevices.isEmpty()) {
            Map<String, String> deviceOptions = [:]
            state.discoveredDevices.each { String thingName, Map info ->
                deviceOptions[thingName] = info.label ?: thingName
            }

            section("<b>Select Venom Devices</b>") {
                input(
                    name: "selectedDevices",
                    type: "enum",
                    title: "Venom Controllers to Manage",
                    options: deviceOptions,
                    multiple: true,
                    required: false,
                    submitOnChange: true
                )
            }
        }

        List<Object> children = getChildDevices()
        if (children) {
            section("<b>Managed Child Devices (${children.size()})</b>") {
                children.each { child ->
                    String temp = child.currentValue("temperature")
                    String target = child.currentValue("heatingSetpoint")
                    String pwr = child.currentValue("switch")
                    paragraph "• <b>${child.displayName}</b> — Power: ${pwr ?: 'unknown'}, Temp: ${temp != null ? temp + '°' : 'N/A'}, Target: ${target != null ? target + '°' : 'N/A'}"
                }
                input name: "btnRefreshAll", type: "button", title: "Refresh All Devices Now"
            }
        }

        section("<b>Polling & Logging</b>") {
            input(
                name: "pollInterval",
                type: "enum",
                title: "Shadow Polling Interval",
                options: POLL_INTERVAL_OPTIONS,
                defaultValue: "30",
                required: true
            )
            input(
                name: "logEnable",
                type: "bool",
                title: "Enable Debug Logging (auto-disables after 30 minutes)",
                defaultValue: false
            )
        }

        section("<b>Advanced AWS IoT Settings</b>", hideable: true, hidden: true) {
            input(
                name: "awsEndpoint",
                type: "text",
                title: "AWS IoT Endpoint",
                defaultValue: DEFAULT_ENDPOINT,
                required: true
            )
            input(
                name: "awsIdentityPoolId",
                type: "text",
                title: "AWS Cognito Identity Pool ID",
                defaultValue: DEFAULT_IDENTITY_POOL_ID,
                required: true
            )
            input(
                name: "awsRegion",
                type: "text",
                title: "AWS Region",
                defaultValue: DEFAULT_REGION,
                required: true
            )
            input name: "btnClearAwsCreds", type: "button", title: "Clear Cached AWS Credentials"
        }
    }
}

def discoverPage() {
    dynamicPage(name: "discoverPage", title: "Discover Spider Grills Devices", nextPage: "mainPage") {
        section("<b>Spider Grills Account Login</b>") {
            paragraph "Enter your Spider Grills email and password, then click <b>Sign In & Discover</b>. " +
                      "Your credentials are used once to fetch your device list and are immediately cleared."
            input name: "spiderEmail", type: "text", title: "Spider Grills Email", required: false
            input name: "spiderPassword", type: "password", title: "Spider Grills Password", required: false
            input name: "btnDiscover", type: "button", title: "Sign In & Discover"
        }

        if (state.discoveryMessage) {
            section("<b>Discovery Status</b>") {
                paragraph state.discoveryMessage
            }
        }
    }
}

def manualAddPage() {
    dynamicPage(name: "manualAddPage", title: "Add Venom Thing Name Manually", nextPage: "mainPage") {
        section("<b>Manual AWS IoT Thing Name</b>") {
            paragraph "If you already know your Venom's AWS IoT Thing Name, you can add it directly without signing into your Spider Grills account."
            input name: "manualThingName", type: "text", title: "AWS IoT Thing Name", required: false
            input name: "manualDeviceLabel", type: "text", title: "Friendly Device Name (optional)", required: false
            input name: "btnAddManual", type: "button", title: "Validate & Add Device"
        }

        if (state.manualMessage) {
            section("<b>Status</b>") {
                paragraph state.manualMessage
            }
        }
    }
}

// ============================================================================
// Button Handler
// ============================================================================

void appButtonHandler(String btn) {
    switch (btn) {
        case "btnDiscover":
            performAccountDiscovery()
            break
        case "btnAddManual":
            performManualAdd()
            break
        case "btnRefreshAll":
            pollAllDevices()
            break
        case "btnClearAwsCreds":
            state.remove("awsCreds")
            log.info "Cleared cached AWS Cognito credentials."
            break
    }
}

// ============================================================================
// Lifecycle
// ============================================================================

void installed() {
    log.info "Spider Grills Venom Manager v${APP_VERSION} installed"
    initialize()
}

void updated() {
    log.info "Spider Grills Venom Manager v${APP_VERSION} updated"
    unschedule()
    initialize()
}

void uninstalled() {
    unschedule()
    getChildDevices().each { child ->
        deleteChildDevice(child.deviceNetworkId)
    }
}

void initialize() {
    syncChildDevices()
    schedulePolling()

    if (logEnable) {
        runIn(1800, "disableDebugLogging")
    }

    if (getChildDevices()) {
        runIn(2, "pollAllDevices")
    }
}

void disableDebugLogging() {
    log.info "Debug logging auto-disabled."
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}

void schedulePolling() {
    unschedule("pollAllDevices")
    Integer interval = (settings.pollInterval ?: "30") as Integer
    if (interval <= 0) {
        logDebug "Automatic polling disabled."
        return
    }

    switch (interval) {
        case 15:
            schedule("0/15 * * * * ?", "pollAllDevices")
            break
        case 30:
            schedule("0/30 * * * * ?", "pollAllDevices")
            break
        case 60:
            runEvery1Minute("pollAllDevices")
            break
        case 300:
            runEvery5Minutes("pollAllDevices")
            break
        case 600:
            runEvery10Minutes("pollAllDevices")
            break
        case 900:
            runEvery15Minutes("pollAllDevices")
            break
        default:
            schedule("0/30 * * * * ?", "pollAllDevices")
            break
    }
    logDebug "Scheduled shadow polling every ${interval} seconds."
}

// ============================================================================
// Child Device Management
// ============================================================================

void syncChildDevices() {
    List<String> selected = settings.selectedDevices instanceof List
        ? settings.selectedDevices
        : (settings.selectedDevices ? [settings.selectedDevices.toString()] : [])

    Set<String> desiredDnis = [] as Set

    selected.each { String thingName ->
        String dni = buildChildDni(thingName)
        desiredDnis.add(dni)
        def existing = getChildDevice(dni)
        if (!existing) {
            Map info = (state.discoveredDevices ?: [:])[thingName] ?: [:]
            String label = info.name ?: "Spider Venom (${thingName})"
            log.info "Creating child device '${label}' (${dni})"
            def child = addChildDevice(
                CHILD_NAMESPACE,
                CHILD_DRIVER_NAME,
                dni,
                [
                    name : CHILD_DRIVER_NAME,
                    label: label,
                    isComponent: false
                ]
            )
            child.updateDataValue("thingName", thingName)
        }
    }

    // Remove unselected child devices
    getChildDevices().each { child ->
        if (!desiredDnis.contains(child.deviceNetworkId)) {
            log.info "Removing unselected child device '${child.displayName}' (${child.deviceNetworkId})"
            deleteChildDevice(child.deviceNetworkId)
        }
    }
}

String buildChildDni(String thingName) {
    return "spider-venom-${thingName}"
}

void pollAllDevices() {
    getChildDevices().each { child ->
        String thingName = child.getDataValue("thingName")
        if (!thingName) {
            thingName = child.deviceNetworkId.replaceFirst(/^spider-venom-/, "")
        }
        try {
            Map shadow = getShadow(thingName)
            if (shadow != null) {
                child.applyShadow(shadow)
            }
        } catch (Exception e) {
            log.warn "Failed to poll Spider Venom '${child.displayName}' (${thingName}): ${e.message}"
        }
    }
}

// ============================================================================
// Account Discovery (One-Time Login — Password Not Stored)
// ============================================================================

void performAccountDiscovery() {
    String email = settings.spiderEmail?.trim()
    String password = settings.spiderPassword

    if (!email || !password) {
        state.discoveryMessage = "<span style='color:red'>Please enter both your Spider Grills email and password.</span>"
        return
    }

    try {
        String accessToken = accountLogin(email, password)
        List<Map> devices = accountGetDevices(accessToken)

        Map<String, Map> discovered = state.discoveredDevices ?: [:]
        int foundCount = 0

        devices.each { Map dev ->
            String thingName = extractThingName(dev)
            if (thingName) {
                String name = extractDeviceName(dev)
                String label = name ? "${name} (${thingName})" : thingName
                discovered[thingName] = [
                    thingName: thingName,
                    name     : name ?: "Spider Venom",
                    label    : label
                ]
                foundCount++
            }
        }

        if (foundCount == 0) {
            state.discoveryMessage = "<span style='color:orange'>Signed in successfully, but no Venom devices were found on this account.</span>"
        } else {
            state.discoveredDevices = discovered
            // Auto-select newly discovered devices if none are selected yet
            if (!settings.selectedDevices) {
                app.updateSetting("selectedDevices", [value: discovered.keySet().toList(), type: "enum"])
            }
            state.discoveryMessage = "<span style='color:green'>Discovered ${foundCount} Venom device(s)! Click Next to select them on the main page.</span>"
        }
    } catch (Exception e) {
        log.error "Spider Grills discovery failed: ${e.message}"
        state.discoveryMessage = "<span style='color:red'>Discovery failed: ${e.message}</span>"
    } finally {
        // Never persist password (or email) after one-time discovery
        app.removeSetting("spiderPassword")
        app.removeSetting("spiderEmail")
    }
}

void performManualAdd() {
    String thingName = settings.manualThingName?.trim()
    String customLabel = settings.manualDeviceLabel?.trim()

    if (!thingName) {
        state.manualMessage = "<span style='color:red'>Please enter an AWS IoT Thing Name.</span>"
        return
    }

    try {
        Map shadow = getShadow(thingName)
        if (shadow == null) {
            throw new RuntimeException("Empty shadow response")
        }
        Map<String, Map> discovered = state.discoveredDevices ?: [:]
        String name = customLabel ?: (shadow?.state?.reported?.model ? "Spider ${shadow.state.reported.model}" : "Spider Venom")
        discovered[thingName] = [
            thingName: thingName,
            name     : name,
            label    : "${name} (${thingName})"
        ]
        state.discoveredDevices = discovered

        List<String> currentSelected = settings.selectedDevices instanceof List
            ? new ArrayList<>(settings.selectedDevices)
            : (settings.selectedDevices ? [settings.selectedDevices.toString()] : [])
        if (!currentSelected.contains(thingName)) {
            currentSelected.add(thingName)
            app.updateSetting("selectedDevices", [value: currentSelected, type: "enum"])
        }

        state.manualMessage = "<span style='color:green'>Validated shadow access for '${thingName}'! Click Next to save.</span>"
        app.removeSetting("manualThingName")
        app.removeSetting("manualDeviceLabel")
    } catch (Exception e) {
        log.error "Manual Thing Name validation failed for '${thingName}': ${e.message}"
        state.manualMessage = "<span style='color:red'>Could not read shadow for '${thingName}': ${e.message}</span>"
    }
}

private String accountLogin(String email, String password) {
    Map response = accountRequest("POST", "/auth/login", [email: email, password: password], null)
    String token = response?.accessToken
    if (!token) {
        throw new RuntimeException("Login response did not include an access token")
    }
    return token
}

private List<Map> accountGetDevices(String accessToken) {
    def response = accountRequest("GET", "/api/devices", null, accessToken)
    List rawDevices
    if (response instanceof List) {
        rawDevices = response
    } else if (response instanceof Map && response.data instanceof List) {
        rawDevices = response.data
    } else if (response instanceof Map && response.devices instanceof List) {
        rawDevices = response.devices
    } else {
        throw new RuntimeException("Devices response had an unexpected shape")
    }
    return rawDevices.findAll { it instanceof Map } as List<Map>
}

private def accountRequest(String method, String path, Map jsonPayload, String accessToken) {
    String url = "${SPIDER_ACCOUNT_BASE_URL}${path}"
    Map<String, String> headers = ["Accept": "application/json"]
    if (accessToken) {
        headers["Authorization"] = "Bearer ${accessToken}"
    }

    Map params = [
        uri        : url,
        headers    : headers,
        contentType: "text/plain",
        timeout    : 15
    ]

    if (jsonPayload != null) {
        params.requestContentType = "application/json"
        params.body = JsonOutput.toJson(jsonPayload)
    }

    String rawBody = ""
    int statusCode = 0

    try {
        if (method == "POST") {
            httpPost(params) { resp ->
                statusCode = resp.status
                rawBody = readResponseBody(resp.data)
            }
        } else {
            httpGet(params) { resp ->
                statusCode = resp.status
                rawBody = readResponseBody(resp.data)
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        statusCode = e.response?.status ?: 0
        rawBody = readResponseBody(e.response?.data)
        logRedactedResponse("Spider Grills API ${method} ${path}", statusCode, rawBody)
        throw new RuntimeException("Spider Grills API returned HTTP ${statusCode}")
    }

    logRedactedResponse("Spider Grills API ${method} ${path}", statusCode, rawBody)

    if (statusCode < 200 || statusCode >= 300) {
        throw new RuntimeException("Spider Grills API returned HTTP ${statusCode}")
    }

    if (!rawBody) {
        return [:]
    }
    return new JsonSlurper().parseText(rawBody)
}

private String extractThingName(Map device) {
    for (String key : ["thingName", "thing_name", "externalId", "deviceId"]) {
        def val = device[key]
        if (val instanceof String && val.trim()) {
            return val.trim()
        }
    }
    return null
}

private String extractDeviceName(Map device) {
    for (String key : ["deviceName", "name", "model"]) {
        def val = device[key]
        if (val instanceof String && val.trim()) {
            return val.trim()
        }
    }
    return null
}

// ============================================================================
// AWS IoT Device Shadow Client (Called by Parent Polling & Child Driver)
// ============================================================================

Map getShadow(String thingName) {
    return iotShadowRequest("GET", thingName, "")
}

Map updateDesiredShadow(String thingName, Map desired) {
    String payload = JsonOutput.toJson([state: [desired: desired]])
    return iotShadowRequest("POST", thingName, payload)
}

private Map iotShadowRequest(String method, String thingName, String payload, boolean isRetry = false) {
    Map creds = getAwsCredentials(isRetry)
    String endpoint = cleanEndpoint(settings.awsEndpoint ?: DEFAULT_ENDPOINT)
    String region = (settings.awsRegion ?: DEFAULT_REGION).trim()
    String path = "/things/${rfc3986Encode(thingName)}/shadow"
    String url = "https://${endpoint}${path}"

    Map<String, String> headers = buildSignedHeaders(
        method,
        endpoint,
        region,
        "iotdata",
        path,
        "",
        payload,
        creds
    )

    Map params = [
        uri        : url,
        headers    : headers,
        contentType: "text/plain",
        timeout    : 15
    ]

    if (method == "POST") {
        params.requestContentType = "application/json"
        params.body = payload
    }

    String rawBody = ""
    int statusCode = 0

    try {
        if (method == "POST") {
            httpPost(params) { resp ->
                statusCode = resp.status
                rawBody = readResponseBody(resp.data)
            }
        } else {
            httpGet(params) { resp ->
                statusCode = resp.status
                rawBody = readResponseBody(resp.data)
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        statusCode = e.response?.status ?: 0
        rawBody = readResponseBody(e.response?.data)
        logRedactedResponse("IoT shadow ${method}", statusCode, rawBody)

        // Retry once with fresh Cognito credentials on 403
        if (statusCode == 403 && !isRetry) {
            logDebug "IoT shadow returned HTTP 403; refreshing Cognito credentials and retrying..."
            state.remove("awsCreds")
            return iotShadowRequest(method, thingName, payload, true)
        }
        String snippet = rawBody.length() > 300 ? rawBody.substring(0, 300) : rawBody
        throw new RuntimeException("IoT shadow request returned HTTP ${statusCode}: ${snippet}")
    }

    logRedactedResponse("IoT shadow ${method}", statusCode, rawBody)

    if (statusCode != 200) {
        String snippet = rawBody.length() > 300 ? rawBody.substring(0, 300) : rawBody
        throw new RuntimeException("IoT shadow request returned HTTP ${statusCode}: ${snippet}")
    }

    def parsed = new JsonSlurper().parseText(rawBody)
    if (!(parsed instanceof Map)) {
        throw new RuntimeException("IoT shadow response had an unexpected shape")
    }
    return parsed as Map
}

// ============================================================================
// AWS Cognito Unauthenticated Identity Credentials
// ============================================================================

private Map getAwsCredentials(boolean forceRefresh = false) {
    Map cached = state.awsCreds
    long nowMs = now()
    if (!forceRefresh && cached && cached.accessKeyId && cached.secretKey && cached.sessionToken && cached.expirationMs) {
        if ((cached.expirationMs as Long) > (nowMs + CREDENTIAL_REFRESH_BUFFER_MS)) {
            return cached
        }
    }

    String region = (settings.awsRegion ?: DEFAULT_REGION).trim()
    String poolId = (settings.awsIdentityPoolId ?: DEFAULT_IDENTITY_POOL_ID).trim()

    Map idResp = cognitoPost(region, "GetId", [IdentityPoolId: poolId])
    String identityId = idResp?.IdentityId
    if (!identityId) {
        throw new RuntimeException("Cognito GetId response did not include IdentityId")
    }

    Map credResp = cognitoPost(region, "GetCredentialsForIdentity", [IdentityId: identityId])
    Map c = credResp?.Credentials
    if (!(c instanceof Map) || !c.AccessKeyId || !c.SecretKey || !c.SessionToken || c.Expiration == null) {
        throw new RuntimeException("Cognito GetCredentialsForIdentity response was incomplete")
    }

    long expirationMs = (Double.parseDouble(c.Expiration.toString()) * 1000.0d) as Long
    Map fresh = [
        accessKeyId : c.AccessKeyId.toString(),
        secretKey   : c.SecretKey.toString(),
        sessionToken: c.SessionToken.toString(),
        expirationMs: expirationMs,
        identityId  : identityId
    ]
    state.awsCreds = fresh
    logDebug "Refreshed temporary AWS Cognito credentials (expires at ${new Date(expirationMs)})"
    return fresh
}

private Map cognitoPost(String region, String target, Map payloadMap) {
    String url = "https://cognito-identity.${region}.amazonaws.com/"
    String bodyJson = JsonOutput.toJson(payloadMap)

    Map params = [
        uri               : url,
        headers           : [
            "X-Amz-Target": "AWSCognitoIdentityService.${target}"
        ],
        requestContentType: "application/x-amz-json-1.1",
        contentType       : "text/plain",
        body              : bodyJson,
        timeout           : 15
    ]

    String rawBody = ""
    int statusCode = 0

    try {
        httpPost(params) { resp ->
            statusCode = resp.status
            rawBody = readResponseBody(resp.data)
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        statusCode = e.response?.status ?: 0
        rawBody = readResponseBody(e.response?.data)
        logRedactedResponse("Cognito ${target}", statusCode, rawBody)
        String errorType = "unknown"
        try {
            def parsedErr = new JsonSlurper().parseText(rawBody)
            if (parsedErr instanceof Map && parsedErr.__type) {
                errorType = parsedErr.__type.toString()
            }
        } catch (ignored) {}
        throw new RuntimeException("Cognito ${target} returned HTTP ${statusCode}: ${errorType}")
    }

    logRedactedResponse("Cognito ${target}", statusCode, rawBody)

    def parsed = new JsonSlurper().parseText(rawBody)
    if (statusCode != 200) {
        String errorType = (parsed instanceof Map && parsed.__type) ? parsed.__type.toString() : "unknown"
        throw new RuntimeException("Cognito ${target} returned HTTP ${statusCode}: ${errorType}")
    }
    if (!(parsed instanceof Map)) {
        throw new RuntimeException("Cognito ${target} response had an unexpected shape")
    }
    return parsed as Map
}

// ============================================================================
// AWS Signature Version 4 (SigV4) Signing
// ============================================================================

private Map<String, String> buildSignedHeaders(
    String method,
    String host,
    String region,
    String service,
    String path,
    String queryString,
    String payload,
    Map creds
) {
    Date nowDate = new Date()
    SimpleDateFormat amzFmt = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'")
    amzFmt.setTimeZone(TimeZone.getTimeZone("UTC"))
    SimpleDateFormat dateFmt = new SimpleDateFormat("yyyyMMdd")
    dateFmt.setTimeZone(TimeZone.getTimeZone("UTC"))

    String amzDate = amzFmt.format(nowDate)
    String dateStamp = dateFmt.format(nowDate)
    String payloadHash = sha256Hex(payload ?: "")

    Map<String, String> canonicalHeaderMap = [
        "host"                : host,
        "x-amz-date"          : amzDate,
        "x-amz-security-token": creds.sessionToken as String
    ]
    List<String> sortedKeys = canonicalHeaderMap.keySet().sort()
    String signedHeaders = sortedKeys.join(";")
    StringBuilder canonicalHeaders = new StringBuilder()
    sortedKeys.each { String k ->
        canonicalHeaders.append(k).append(":").append(canonicalHeaderMap[k]).append("\n")
    }

    String canonicalRequest = [
        method,
        path,
        queryString,
        canonicalHeaders.toString(),
        signedHeaders,
        payloadHash
    ].join("\n")

    String credentialScope = "${dateStamp}/${region}/${service}/aws4_request"
    String stringToSign = [
        "AWS4-HMAC-SHA256",
        amzDate,
        credentialScope,
        sha256Hex(canonicalRequest)
    ].join("\n")

    byte[] signingKey = getSignatureKey(creds.secretKey as String, dateStamp, region, service)
    String signature = hmacSha256(signingKey, stringToSign).encodeHex().toString()

    String authHeader = "AWS4-HMAC-SHA256 " +
        "Credential=${creds.accessKeyId}/${credentialScope}, " +
        "SignedHeaders=${signedHeaders}, " +
        "Signature=${signature}"

    return [
        "Authorization"       : authHeader,
        "x-amz-date"          : amzDate,
        "x-amz-security-token": creds.sessionToken as String
    ]
}

private byte[] getSignatureKey(String secretKey, String dateStamp, String region, String service) {
    byte[] kDate = hmacSha256(("AWS4" + secretKey).getBytes("UTF-8"), dateStamp)
    byte[] kRegion = hmacSha256(kDate, region)
    byte[] kService = hmacSha256(kRegion, service)
    return hmacSha256(kService, "aws4_request")
}

private byte[] hmacSha256(byte[] key, String data) {
    Mac mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data.getBytes("UTF-8"))
}

private String sha256Hex(String text) {
    MessageDigest md = MessageDigest.getInstance("SHA-256")
    return md.digest(text.getBytes("UTF-8")).encodeHex().toString()
}

private String rfc3986Encode(String value) {
    return URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~")
}

private String cleanEndpoint(String endpoint) {
    return endpoint
        .replaceFirst(/^https?:\/\//, "")
        .replaceAll(/^\/+|\/+$/, "")
}

private String readResponseBody(def data) {
    if (data == null) {
        return ""
    }
    if (data instanceof String) {
        return data
    }
    if (data instanceof java.io.Reader) {
        return data.text
    }
    if (data instanceof java.io.InputStream) {
        return data.getText("UTF-8")
    }
    if (data instanceof Map || data instanceof List) {
        return JsonOutput.toJson(data)
    }
    return data.toString()
}

// ============================================================================
// Logging Helpers (Redacted)
// ============================================================================

private void logDebug(String msg) {
    if (settings.logEnable) {
        log.debug msg
    }
}

private void logRedactedResponse(String label, int status, String body) {
    if (!settings.logEnable) {
        return
    }
    log.debug "${label} returned HTTP ${status} body: ${redactBody(body)}"
}

private String redactBody(String body) {
    if (!body) {
        return ""
    }
    try {
        def parsed = new JsonSlurper().parseText(body)
        return JsonOutput.toJson(redactValue(parsed))
    } catch (ignored) {
        return body
    }
}

private def redactValue(def value) {
    if (value instanceof Map) {
        Map out = [:]
        value.each { k, v ->
            String keyStr = k.toString()
            out[keyStr] = isSensitiveKey(keyStr) ? "***REDACTED***" : redactValue(v)
        }
        return out
    }
    if (value instanceof List) {
        return value.collect { redactValue(it) }
    }
    return value
}

private boolean isSensitiveKey(String key) {
    String normalized = key.toLowerCase()
    return REDACTED_KEYS.contains(normalized) || normalized.endsWith("token")
}
