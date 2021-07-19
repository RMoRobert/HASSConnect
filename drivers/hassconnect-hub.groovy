/**
 * =============================  HASSConnect Home Assistant Hub (Driver) ===============================
 *
 *  Copyright 2021 Robert Morris
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * =======================================================================================
 *
 *  Last modified: 2021-07-18
 *
 *  Changelog:
 *  v0.9.1  - (Beta) Added media_player entities (preliminary Chromecast support)
 *  v0.9    - (Beta) Initial Public Release
 */ 

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static Map<Long,Long> id = [:]
@Field static final Boolean boolIgnoreSSLIssues = true

// Criteria for device filtering from HASS for different Hubitat device types, generally based on
// HASS domain or device_class borderline-heuristics (may need adjusment for some specific devices)
@Field static final Map<String,Map<String,Object>> deviceSelectors = [
   "switch": [uiName: "switches",
              driver: "Generic Component Switch", driverNamespace: "hubitat",
              detectionClosure: {Map m -> m.entity_id?.startsWith("switch.")}],
   motion:   [uiName: "motion sensors",
              driver: "Generic Component Motion Sensor", driverNamespace: "hubitat",
              detectionClosure: {Map m -> m.attributes?.device_class in deviceClasses["motion"]}],
   contact:  [uiName: "contact sensors",
              driver: "Generic Component Contant Sensor", driverNamespace: "hubitat",
              detectionClosure: {Map m -> m.attributes?.device_class in deviceClasses["contact"]}],
   mediaPlayer:  [uiName: "media players",
              driver: "HASSConnect Media/Speech Device", driverNamespace: "RMoRobert",
              detectionClosure: {Map m -> m.entity_id?.startsWith("media_player.")}]
]

// Sensor and binary sensor HASS device_class to Hubitat capability mappings (where needed)
// Required only for devices where deviceSelectors map (above) or state parsing (way below) use these
@Field static final Map<String,List<String>> deviceClasses = [
   "motion": ["motion", "presence", "occupancy"],
   "contact": ["door", "garage_door", "window"]
]

// Device caches (could use state, but this is likely a less expensive alternative for temporary storage)
// Name must match deviceSelector string key above plus "Cache", e.g., "motion" + "Cache" = "motionCache"
// Once accessed by device ID, cache is in Map with [hass_entity_id: hass_friendly_name] format
// Required for all deviceSelectors keys (so add new entry here if add one to deviceSelectors above)
@Field static Map<Long,Map<String,String>> switchCache = [:]
@Field static Map<Long,Map<String,String>> motionCache = [:]
@Field static Map<Long,Map<String,String>> contactCache = [:]
@Field static Map<Long,Map<String,String>> mediaPlayerCache = [:]

metadata {
   definition (name: "HASSConnect Home Assistant Hub", namespace: "RMoRobert", author: "Robert Morris",
               importUrl: "https://raw.githubusercontent.com/RMoRobert/HASSConnect/main/drivers/hassconnect-hub.groovy") {
      capability "Actuator"
      capability "Refresh"
      capability "Initialize"
      attribute "status", "string"

      //Useful for testing:
      //command "fetchDevices", [[name:"Device type", type: "STRING", description: ""]]
      command "fetchEvents"
   }
   
   preferences() {
      input name: "ipAddress", type: "string", title: "IP address", required: true
      input name: "port", type: "number", title: "Port", required: true
      input name: "accessToken", type: "string", title: "Long-lived access token", required: true
      input name: "useSecurity", type: "bool", title: "Use TLS"
      input name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "enableDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true
   }   
}

void debugOff() {
   log.warn "Disabling debug logging"
   device.updateSetting("enableDebug", [value:"false", type:"bool"])
}

void installed() {
   log.debug "installed()"
   initialize()
}

void updated() {
   log.debug "updated()"
   initialize()
}

void initialize() {
   if (enableDebug) log.debug "initialize()"
   connectWebSocket()
   if (enableDebug) {
      Integer disableTime = 1800
      log.debug "Debug logging will be automatically disabled in ${disableTime/60} minutes"
      runIn(disableTime, debugOff)
   }
}

void connectWebSocket() {
   if (enableDebug) log.debug "connectWebSocket()"
   id[device.id] = 2
   if (settings.useSecurity) {
      interfaces.webSocket.connect("wss://${ipAddress}:${port}/api/websocket", ignoreSSLIssues: boolIgnoreSSLIssues)
   }
   else {
      interfaces.webSocket.connect("ws://${ipAddress}:${port}/api/websocket")
   }
}

void webSocketStatus(String msg) {
   if (enableDebug) log.debug "webSocketStatus: $msg"
   if (msg?.startsWith("status: ")) msg = msg.substring(8) // remove "status: " from string
   doSendEvent("status", msg)
   if (msg.contains("open")) {
      // 'closing' and 'open' seem to happen such quick succession that some extra time might be needed not to overlap previous execution:
      pauseExecution(700)
      state.connectionRetryTime = 5
      unschedule("connectWebSocket")
   }
   else {
      if (state.connectionRetryTime) {
         state.connectionRetryTime *= 2
         if (state.connectionRetryTime > 900) {
            state.connectionRetryTime = 900 // cap retry time at 15 minutes
         }
      }
      else {
         state.connectionRetryTime = 5
      }
      runIn(state.connectionRetryTime, "connectWebSocket")
   }
}

/** Handles incoming messages from WebSocket
*/
void parse(String description) {
   if (enableDebug) log.debug "parse(): $description"
   Map parsedMap = new JsonSlurper().parseText(description)
   if (parsedMap) {
      // ***** For responding to auth requests: *****
      if (parsedMap["type"] == "auth_required") {
         if (enableDebug) log.debug "type = auth_required; sending authorization..."
         sendCommand([type: "auth", access_token: accessToken])
      }
      else if (parsedMap["type"] == "auth_ok") {
         if (enableDebug) log.debug "type = auth_ok; requesting subscriptions..."
         // Subscribe to events--not filtering now, but commented out seconds below may cover
         // all that's needed if becomes necessary:
         sendCommand([id: id[device.id] ?: 2, type: "subscribe_events"/*, event_type: "state_changed"*/])
         //sendCommand([id: id[device.id] ?: 2, type: "subscribe_events", event_type: "zha_event"])
      }
      //log.error "id = ${parsedMap['id']}"
      // ***** Regular events: *****
      if (parsedMap["type"] == "event" || parsedMap["event_type"] == "state_changed" ) {
         if (parsedMap.event?.data?.entity_id) {
            if (enableDebug) "parsing as event..."
            parseDeviceState(parsedMap.event)
         }
         else if  (parsedMap.data?.entity_id) {
            if (enableDebug) log.debug "parsing as state change..."
            parseDeviceState(parsedMap)
         }
         else {
            log.trace "Skipping...<br> ${JsonOutput.prettyPrint(description)}"
         }
      }
      else {
         if (enableDebug) log.debug "Skipping because not type=event"
      }      
   }
   else {
      if (enableDebug) log.debug "Not parsing; map empty"
   }
}

void parseDeviceState(Map event) {
   log.debug "parseDeviceState($event)"
   //log.trace JsonOutput.prettyPrint(JsonOutput.toJson(event))
   List<Map> evts = []
   com.hubitat.app.ChildDeviceWrapper dev
   // Set "deviceType" to device_class for sensors, binary sensors, etc; otherwise, use entity's domain
   // to classify (e.g., light.hallway1 is "light"), then generate events as needed based on parsing HASS
   // event data if matching device is present on Hubitat
   String deviceType = event.data.new_state.attributes.device_class
   String entityId =  event.data.entity_id ?: event.data.service_data?.entity_id
   if (!deviceType) deviceType = entityId.tokenize(".")[0]
   switch (deviceType) {
      case "switch":
         String dni = "${device.deviceNetworkId}/switch/${entityId}"
         dev = getChildDevice(dni); if (dev == null) break
         String value = (event.data.new_state.state == "on") ? "on" : "off"
         evts << [name: "switch", value: value,
                  descriptionText: "${dev?.displayName} switch is $value"]
         break
      case {it in motionSensorDeviceClasses}:
         String dni = "${device.deviceNetworkId}/motion/${entityId}"
         dev = getChildDevice(dni); if (dev == null) break
         String value = (event.data.new_state.state == "on") ? "active" : "inactive"
         evts << [name: "motion", value: value,
                  descriptionText: "${dev?.displayName} motion is $value"]
         break
      case "media_player":
         String dni = "${device.deviceNetworkId}/mediaPlayer/${entityId}"
         dev = getChildDevice(dni); if (dev == null) break
         String value = (event.data.new_state.state == "on") ? "active" : "inactive"
         evts << [name: "motion", value: value,
                  descriptionText: "${dev?.displayName} motion is $value"]
         break
         break
      default:
         if (enableDebug) log.debug "skipping class $deviceType"
   }
   // Send events to child device (if found):
   dev?.parse(evts)
}

void sendCommand(Map command) {
   if (enableDebug) log.debug "sendCommand($command)"
   log.trace "id = ${id[device.id]}"
   id[device.id] = id[device.id] ? id[device.id] + 1 : 2
   if (!(command.id) && !(command.type == "auth")) command += [id: id[device.id]]
   String msg = JsonOutput.toJson(command)
   if (enableDebug) log.debug "sendCommand JSON: $msg"
   interfaces.webSocket.sendMessage(msg)
}

void refresh() {
   if (enableDebug) log.debug "refresh()"
   //log.trace this."$motionSensorDeviceClasses"
   Map params = [
      uri: "http://${ipAddress}:${port}/api/states",
      contentType: "application/json",
      headers: [Authorization: "Bearer ${accessToken}"],
      timeout: 15
   ]
   try {
      asynchttpGet("parseStates", params)
   } catch (Exception ex) {
      log.error "Error in refresh: $ex"
   }
}

/**
 * Callback method that handles full Bridge refresh. Eventually delegated to individual
 * methods below.
 */
private void parseStates(resp, data) {
   //resp.json.each { log.trace it }
   if (enableDebug) log.debug "parseStates: States from Bridge received. Now parsing..."
   if (checkIfValidResponse(resp)) {
      parseLightStates(resp.json.lights)
      parseGroupStates(resp.json.groups)
      parseSensorStates(resp.json.sensors)
      parseLabsSensorStates(resp.json.sensors)
   }
}

/**
 * Callback method that handles full Bridge refresh. Eventually delegated to individual
 * methods below.
 */
private void handleGenericResponse(resp, data) {
   resp.json.each { 
      log.trace it

   }
   if (enableDebug) log.debug "parseStates: States from Bridge received. Now parsing..."
   if (checkIfValidResponse(resp)) {
      parseLightStates(resp.json.lights)
      parseGroupStates(resp.json.groups)
      parseSensorStates(resp.json.sensors)
      parseLabsSensorStates(resp.json.sensors)
   }
}



// ----------- Device-fetching methods -------------

/**
 * Intended to be called from parent app to request that this driver fetch and cache devices of
 * specifc type, e.g., all motion sensors or switches.
 * @param deviceSelector Device selector/type, e.g., "contact" or "switch" (from deviceSelectors field keys)
 */
void fetchDevices(String deviceClass) {
   if (enableDebug) log.debug "fetchDevices($deviceClass)"
   Map params = [
      uri: "http://${ipAddress}:${port}/api/states",
      contentType: "application/json",
      headers: [Authorization: "Bearer ${accessToken}"],
      timeout: 15
   ]
   try {
      // Using HTTP for this (see if can use websocket, too?)
      // Note: passing deviceClass as data parameter so callback knows what to do
      asynchttpGet("parseFetchDevicesResponse", params, [deviceClass: deviceClass])
   } catch (Exception ex) {
      log.error "Error in fetchDevices(): $ex"
   }
}

void fetchEvents() {
   if (enableDebug) log.debug "fetchEvents()"
   Map params = [
      uri: "http://${ipAddress}:${port}/api/events",
      contentType: "application/json",
      headers: [Authorization: "Bearer ${accessToken}"],
      timeout: 15
   ]
   try {
      // Using HTTP for this (see if can use websocket, too?)
      // Note: passing deviceClass as data parameter so callback knows what to do
      asynchttpGet("parseFetchDevicesResponse", params)
   } catch (Exception ex) {
      log.error "Error in fetchDevices(): $ex"
   }
}

// Callback for fetchDevices(), above -- filter list to devices of specific type, then store in cache
private void parseFetchDevicesResponse(resp, Map data) {
   if (enableDebug) log.debug "parseFetchDevicesResponse(${resp.json} ..., data: $data)"
   if (resp.status < 300) {
      if (resp.getJson()) {
         Map<String,String> devices = [:]
         resp.json.findAll(deviceSelectors[data.deviceClass].detectionClosure)?.each {
               if (enableDebug) log.debug "Critera matched for entity ${it.entity_id}"
               devices[it.entity_id] = it.attributes?.friendly_name ?: it.entity_id
         }
         if (enableDebug) log.debug "Finished finding devices; devices = $devices"
         this."${data.deviceClass}Cache"[device.id] = devices
      }
      else {
         log.warn "No JSON found in response in parseFetchDevicesResponse(). HTTP ${resp.status}"
      }
   }
   else {
      log.error "HTTP stauts: ${resp.status} in parseFetchDevicesResponse()"
   }
}


/**
 * Intended to be called from parent app to retrive previously
 * requested list of devices of specific type
 * @param deviceSelector Device selector/type, e.g., "contact" or "switch" (matches "xyz" of xyzCache field)
 */
Map getCache(String deviceSelector) {
   if (enableDebug) log.debug "getCache($deviceSelector)"
   if (enableDebug) log.debug """Returning ${this."${deviceSelector}Cache"[device.id]}"""
   return this."${deviceSelector}Cache"[device.id]
}

/** 
 * Clears cache of either specific device type or all devices
 * @param deviceSelector Device selector/type, e.g., "contact" or "switch" (matches "xyz" of xyzCache field)
 */
void clearCache(String deviceSelector) {
   if (enableDebug) log.debug "clearCache($deviceSelector)"
   this."${deviceSelector}Cache"[device.id] = [:]
}

private void doSendEvent(String eventName, eventValue) {
   //if (enableDebug) log.debug ("Creating event for $eventName...")
   String descriptionText = "${device.displayName} ${eventName} is ${eventValue}"
   if (settings.enableDesc) log.info descriptionText
   sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText)
}

/**
 * Returns deviceSelectors field, intended to be called by parent app for listing selector
 * pages in UI
 */
Map<String,Map<String,Object>> getDeviceSelectors() {
   return deviceSelectors
}

///////////////////////////////////////
// Component device methods:
///////////////////////////////////////

void componentOn(com.hubitat.app.DeviceWrapper dev) {
   if (enableDebug) log.debug "componentOn(${dev.displayName})"
   // DNI in format "Hc/appID/DomainOrDeviceType/EntityID", so can split on "/" to get entity_id:
   String entityId = dev.deviceNetworkId.tokenize("/")[3]
   String domain = entityId.tokenize(".")[0]
   Map cmd = [type: "call_service", service: "turn_on", domain: domain, service_data: [entity_id: entityId]]
   log.warn "cmd = $cmd"
   sendCommand(cmd)
}

void componentOff(com.hubitat.app.DeviceWrapper dev) {
   if (enableDebug) log.debug "componentOff(${dev.displayName})"
   // DNI in format "Hc/appID/DomainOrDeviceType/EntityID", so can split on "/" to get entity_id:
   String entityId = dev.deviceNetworkId.tokenize("/")[3]
   String domain = entityId.tokenize(".")[0]
   Map cmd = [type: "call_service", service: "turn_off", domain: domain, service_data: [entity_id: entityId]]
   log.warn "cmd = $cmd"
   sendCommand(cmd)
}

void componentSpeak(com.hubitat.app.DeviceWrapper dev, String text, Number volume, String voice, String service=null) {
   if (enableDebug) log.debug "componentSpeak(${dev.displayName}, $text, $volume = null, $voice = null)"
   // DNI in format "Hc/appID/DomainOrDeviceType/EntityID", so can split on "/" to get entity_id:
   String entityId = dev.deviceNetworkId.tokenize("/")[3]
   Map cmd = [type: "call_service", service: service ?: "google_translate_say", domain: "tts", service_data: [entity_id: entityId, message: text]]
   log.warn "cmd = $cmd"
   sendCommand(cmd)
}