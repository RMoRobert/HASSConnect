/**
 * =============================  HASSConnect Home Assistant Hub (Driver) ===============================
 *
 *  Copyright 2022 Robert Morris
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
 *  HASS REST API Reference: https://developers.home-assistant.io/docs/api/rest/
 *
 *  Last modified: 2022-09-05
 *
 *  Changelog:
 *  v0.9.4  - Add presence/zone device support
 *  v0.9.3  - Add Alexa Media Player TTS support
 *  v0.9.3  - (Beta) Added ZHA button (event) support
 *  v0.9.2  - (Beta) Added RGBW light support, improved reconnection and concurrency issues
 *  v0.9.1  - (Beta) Added media_player entities (preliminary Chromecast support); improved reconnection algorithm
 *  v0.9    - (Beta) Initial Public Release
 */ 

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field static ConcurrentHashMap<Long,Long> id = [:]
@Field static final Boolean boolIgnoreSSLIssues = true

// Criteria for device filtering from HASS for different Hubitat device types, generally based on
// HASS domain or device_class borderline-heuristics (may need adjusment for some specific devices)
@Field static final Map<String,Map<String,Object>> deviceSelectors = [
   "switch": [uiName: "switches",
              driver: "Generic Component Switch", driverNamespace: "hubitat",
              detectionClosure: {Map m -> m.entity_id?.startsWith("switch.")}],
   lightRGBW: [uiName: "lights (RGBW)",
              driver: "Generic Component RGBW", driverNamespace: "hubitat",
              detectionClosure: {Map m -> m.entity_id?.startsWith("light.") &&
                                           m.attributes?.supported_color_modes?.contains("hs") &&
                                          m.attributes?.supported_color_modes?.contains("color_temp")}],
   motion:   [uiName: "motion sensors",
              driver: "Generic Component Motion Sensor", driverNamespace: "hubitat",
              detectionClosure: {Map m -> m.attributes?.device_class in deviceClasses["motion"]}],
   contact:  [uiName: "contact sensors",
              driver: "Generic Component Contact Sensor", driverNamespace: "hubitat",
              detectionClosure: {Map m -> m.attributes?.device_class in deviceClasses["contact"]}],
   presence:  [uiName: "presence sensors",
              driver: "Generic Component Presence Sensor", driverNamespace: "hubitat",
              detectionClosure: {Map m -> m.entity_id?.startsWith("device_tracker.")}],
   mediaPlayer:  [uiName: "media players",
              driver: "HASSConnect Chromecast TTS Device", driverNamespace: "RMoRobert",
              detectionClosure: {Map m -> m.entity_id?.startsWith("media_player.")},
              uiNotes:"This has been tested only with Google Chromecast devices. Not all features may work with all devices."]
]

// Sensor and binary sensor HASS device_class to Hubitat capability mappings (where needed)
// Required only for devices where deviceSelectors map (above) or state parsing (way below) use these
@Field static final Map<String,List<String>> deviceClasses = [
   motion: ["motion", "presence", "occupancy"],
   contact: ["door", "garage_door", "window"]
]

// Device caches (could use state, but this is likely a less expensive alternative for temporary storage)
// Name must match deviceSelector string key above plus "Cache", e.g., "motion" + "Cache" = "motionCache"
// Once accessed by device ID, cache is in Map with [hass_entity_id: hass_friendly_name] format
// Required for all deviceSelectors keys (so add new entry here if add one to deviceSelectors above)
@Field static ConcurrentHashMap<Long,Map<String,String>> switchCache = [:]
@Field static ConcurrentHashMap<Long,Map<String,String>> lightRGBWCache = [:]
@Field static ConcurrentHashMap<Long,Map<String,String>> motionCache = [:]
@Field static ConcurrentHashMap<Long,Map<String,String>> contactCache = [:]
@Field static ConcurrentHashMap<Long,Map<String,String>> presenceCache = [:]
@Field static ConcurrentHashMap<Long,Map<String,String>> mediaPlayerCache = [:]

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
      input name: "logEnable", type: "bool", title: "Enable debug logging"
      input name: "txtEnable", type: "bool", title: "Enable descriptionText logging"
   }   
}

void debugOff() {
   log.warn "Disabling debug logging"
   device.updateSetting("logEnable", [value:"false", type:"bool"])
}

void installed() {
   log.debug "installed()"
   runIn(4, "initialize")
}

void updated() {
   log.debug "updated()"
   initialize()
}

void initialize() {
   if (logEnable) log.debug "initialize()"
   connectWebSocket()
   if (logEnable) {
      Integer disableTime = 1800
      log.debug "Debug logging will be automatically disabled in ${disableTime/60} minutes"
      runIn(disableTime, debugOff)
   }
}

void connectWebSocket() {
   if (logEnable) log.debug "connectWebSocket()"
   id[device.id] = 2
   if (settings.useSecurity) {
      interfaces.webSocket.connect("wss://${ipAddress}:${port}/api/websocket", ignoreSSLIssues: boolIgnoreSSLIssues)
   }
   else {
      interfaces.webSocket.connect("ws://${ipAddress}:${port}/api/websocket")
   }
}

void reconnectWebSocket(Boolean notIfAlreadyConnected = true) {
   if (logEnable) log.debug "reconnectWebSocket()"
   if (device.currentValue("status") == "open" && notIfAlreadyConnected) {
      if (logEnable) log.debug "already connected; skipping reconnection"
   }
   else {
      connectWebSocket()
   }
}

void webSocketStatus(String msg) {
   if (logEnable) log.debug "webSocketStatus: $msg"
   if (msg?.startsWith("status: ")) msg = msg.substring(8) // remove "status: " from string
   doSendEvent("status", msg)
   if (msg.contains("open")) {
      // 'closing' and 'open' seem to happen such quick succession that some extra time might be needed not to overlap previous execution:
      state.connectionRetryTime = 5
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
      runIn(state.connectionRetryTime, "reconnectWebSocket")
   }
}

/** Handles incoming messages from WebSocket
*/
void parse(String description) {
   if (logEnable) log.debug "parse(): $description"
   Map parsedMap = new JsonSlurper().parseText(description)
   if (parsedMap) {
      // Responding to auth reuqeust
      if (parsedMap["type"] == "auth_required") {
         parseAuthRequired(parsedMap)
      }
      // Authorization OK; will then subscribe
      else if (parsedMap["type"] == "auth_ok") {
         parseAuthOK(parsedMap)
      }
      // ***** Regular events: *****
      if (parsedMap["type"] == "event" || parsedMap["event_type"] == "state_changed" ) {
         if (parsedMap.event?.data?.entity_id) {
            if (logEnable) "parsing as event..."
            parseDeviceState(parsedMap.event)
         }
         else if (parsedMap.data?.entity_id) {
            if (logEnable) log.debug "parsing as state change..."
            parseDeviceState(parsedMap)
         }
         else if (parsedMap.event?.event_type == "zha_event" && parsedMap.event.data?.device_ieee) {
            parseButtonEvents(parsedMap.event.event_type, parsedMap.event.data)
         }
         else if (parsedMap["type"] == "result" && parsedMap["success"] == false && parsedMap.error?.code == "id_reuse") {
            // should also reset ID
            log.warn "id mismatch; resetting socket (parsedMap = $parsedMap)"
            connectWebSocket()
         }
         else {
            log.trace "Skipping...<br> ${JsonOutput.prettyPrint(description)}"
         }
      }
      else {
         if (logEnable) log.debug "Skipping because not type=event or event_type=state_changed"
      }
   }
   else {
      if (logEnable) log.debug "Not parsing; map empty"
   }
}

void parseAuthRequired(Map parsedMap) {
   if (logEnable) log.debug "type = auth_required; sending authorization..."
   sendCommand([type: "auth", access_token: accessToken])
}

void parseAuthOK(Map parsedMap) {
   if (logEnable) log.debug "type = auth_ok; requesting subscriptions..."
   // Subscribe to events--not filtering now, but commented out sections below may cover
   // all that's needed if becomes necessary:
   sendCommand([id: id[device.id] ?: 2, type: "subscribe_events"])
   //sendCommand([id: id[device.id] ?: 2, type: "subscribe_events", event_type: "state_changed"])
   //sendCommand([id: id[device.id] ?: 2, type: "subscribe_events", event_type: "zha_event"])
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
      case "light":
         String dni = "${device.deviceNetworkId}/lightRGBW/${entityId}"
         dev = getChildDevice(dni)
         // Backup ... may need to add mroe of these in case if/when add more devices
         if (dev == null) {
            dni = "${device.deviceNetworkId}/light/${entityId}"
            dev = getChildDevice(dni)
         }
         if (dev == null) {
            break
         }
         //log.warn "${JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(event.data.new_state))}"
         if (event.data.new_state.state) {
            String value = (event.data.new_state.state == "on") ? "on" : "off"
            evts << [name: "switch", value: value,
                  descriptionText: "${dev?.displayName} switch is $value"]
         }
         if (event.data.new_state.attributes.color_mode == "hs") {
            evts << [name: "colorMode", value: "RGB",
                  descriptionText: "${dev?.displayName} colorMode is RGB"]
            if (event.data.new_state.attributes.hs_color) {
               Integer value =  Math.round(event.data.new_state.attributes.hs_color[0]/3.6)
               evts << [name: "hue", value: value,
                     descriptionText: "${dev?.displayName} hue is $value"]
               value = event.data.new_state.attributes.hs_color[1]
               evts << [name: "saturation", value: value,
                     descriptionText: "${dev?.displayName} saturation is $value"]
            }
            else if (event.data.new_state.attributes.rgb_color) {
               if (logEnable) log.warn "ignoring rgb color (todo)"
            }
            else if (event.data.new_state.attributes.xy) {
               if (logEnable) log.warn "ignoring xy color (todo)"
            }
         }
         if (event.data.new_state.attributes.color_mode == "color_temp") {
            evts << [name: "colorMode", value: "CT",
                  descriptionText: "${dev?.displayName} colorMode is CT"]
            if (event.data.new_state.attributes.color_temp) {
               Integer value =  Math.round(1000000/event.data.new_state.attributes.color_temp)
               evts << [name: "colorTemperature", value: value,
                     descriptionText: "${dev?.displayName} colorTemperature is $value"]
            }
         }
         if (event.data.new_state.attributes.brightness != null) {
            Integer value = Math.round(event.data.new_state.attributes.brightness/2.55)
            evts << [name: "level", value: value,
                  descriptionText: "${dev?.displayName} level is $value"]
         }
         break
      case {it in deviceClasses.motion}:
         String dni = "${device.deviceNetworkId}/motion/${entityId}"
         dev = getChildDevice(dni); if (dev == null) break
         String value = (event.data.new_state.state == "on") ? "active" : "inactive"
         evts << [name: "motion", value: value,
                  descriptionText: "${dev?.displayName} motion is $value"]
         break
      case "device_tracker":
         String dni = "${device.deviceNetworkId}/presence/${entityId}"
         dev = getChildDevice(dni); if (dev == null) break
         String value = (event.data.new_state.state == "home") ? "present" : "not present"
         evts << [name: "presence", value: value,
                  descriptionText: "${dev?.displayName} presence is $value"]
         break
      case "media_player":
         String dni = "${device.deviceNetworkId}/mediaPlayer/${entityId}"
         dev = getChildDevice(dni); if (dev == null) break
         if (event.data.new_state.state in ["on", "idle"]) value = "on"
         else if (event.data.new_state.state == "off") value = "off"
         if (value) {
         evts << [name: "switch", value: value,
                  descriptionText: "${dev?.displayName} switch is $value"]
         }
         break
      default:
         if (logEnable) log.debug "skipping class $deviceType"
   }
   // Send events to child device (if found):
   dev?.parse(evts)
}

void parseButtonEvents(String eventType, Map eventData) {
   if (logEnable) log.debug "parseButtonEvents(eventType = $eventType, eventData = $eventData)"
   com.hubitat.app.ChildDeviceWrapper dev
   if (eventType == "zha_event") {
      String dni = "${device.deviceNetworkId}/zha_button/${eventData.device_ieee}"
      dev = getChildDevice(dni); if (dev == null) return
      dev.buttonParse(eventType, eventData)
   }
   else {
      if (logEnable) log.debug "Skipping unknown eventType $eventType"
   }
}

void sendCommand(Map command) {
   if (logEnable) log.debug "sendCommand($command)"
   //log.trace "id = ${id[device.id]}"
   id[device.id] = id[device.id] ? id[device.id] + 1 : 2
   if (!(command.id) && !(command.type == "auth")) command += [id: id[device.id]]
   String msg = JsonOutput.toJson(command)
   if (logEnable) log.debug "sendCommand JSON: $msg"
   interfaces.webSocket.sendMessage(msg)
}

void refresh() {
   if (logEnable) log.debug "refresh()"
   Map params = [
      uri: useSecurity ? "https://${ipAddress}:${port}/api/states" : "http://${ipAddress}:${port}/api/states",
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


// ----------- Device-fetching methods -------------

/**+
 * Intended to be called from parent app to request that this driver fetch and cache devices of
 * specifc type, e.g., all motion sensors or switches.
 * @param deviceSelector Device selector/type, e.g., "contact" or "switch" (from deviceSelectors field keys)
 */
void fetchDevices(String deviceClass) {
   if (logEnable) log.debug "fetchDevices($deviceClass)"
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
   if (logEnable) log.debug "fetchEvents()"
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
   if (logEnable) log.debug "parseFetchDevicesResponse(${resp.json} ..., data: $data)"
   if (resp.status < 300) {
      if (resp.getJson()) {
         Map<String,String> devices = [:]
         resp.json.findAll(deviceSelectors[data.deviceClass].detectionClosure)?.each {
               if (logEnable) log.debug "Critera matched for entity ${it.entity_id}"
               else { log.warn it }
               devices[it.entity_id] = it.attributes?.friendly_name ?: it.entity_id
         }
         if (logEnable) log.debug "Finished finding devices; devices = $devices"
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
   if (logEnable) log.debug "getCache($deviceSelector)"
   if (logEnable) log.debug """Returning ${this."${deviceSelector}Cache"[device.id]}"""
   return this."${deviceSelector}Cache"[device.id]
}

/** 
 * Clears cache of either specific device type or all devices
 * @param deviceSelector Device selector/type, e.g., "contact" or "switch" (matches "xyz" of xyzCache field)
 */
void clearCache(String deviceSelector) {
   if (logEnable) log.debug "clearCache($deviceSelector)"
   this."${deviceSelector}Cache"[device.id] = [:]
}

private void doSendEvent(String eventName, eventValue) {
   //if (logEnable) log.debug ("Creating event for $eventName...")
   String descriptionText = "${device.displayName} ${eventName} is ${eventValue}"
   if (settings.txtEnable) log.info descriptionText
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
   if (logEnable) log.debug "componentOn(${dev.displayName})"
   // DNI in format "Hc/appID/DomainOrDeviceType/EntityID", so can split on "/" to get entity_id:
   String entityId = dev.deviceNetworkId.tokenize("/")[3]
   String domain = entityId.tokenize(".")[0]
   Map cmd = [type: "call_service", service: "turn_on", domain: domain, service_data: [entity_id: entityId]]
   sendCommand(cmd)
}

void componentOff(com.hubitat.app.DeviceWrapper dev) {
   if (logEnable) log.debug "componentOff(${dev.displayName})"
   // DNI in format "Hc/appID/DomainOrDeviceType/EntityID", so can split on "/" to get entity_id:
   String entityId = dev.deviceNetworkId.tokenize("/")[3]
   String domain = entityId.tokenize(".")[0]
   Map cmd = [type: "call_service", service: "turn_off", domain: domain, service_data: [entity_id: entityId]]
   sendCommand(cmd)
}

void componentRefresh(com.hubitat.app.DeviceWrapper dev) {
   if (logEnable) log.debug "componentRefresh(${dev.displayName})"
   log.trace "not implemented: componentRefresh for ${dev.displayName}"
}

void componentSetLevel(com.hubitat.app.DeviceWrapper dev, Number level, Number transitionTime=null) {
   if (logEnable) log.debug "componentSetLevel(${dev.displayName}, $level)"
   // DNI in format "Hc/appID/DomainOrDeviceType/EntityID", so can split on "/" to get entity_id:
   String entityId = dev.deviceNetworkId.tokenize("/")[3]
   String domain = entityId.tokenize(".")[0]
   Map svcData = [entity_id: entityId, brightness_pct: level as Integer]
   if (transitionTime != null) svcData << [transition: transitionTime]
   Map cmd = [type: "call_service", service: "turn_on", domain: domain, service_data: svcData]
   sendCommand(cmd)
}

void componentStartLevelChange(com.hubitat.app.DeviceWrapper dev, String direction) {
   if (logEnable) log.debug "componentStartLevelChange(${dev.displayName}, $direction)"
   // DNI in format "Hc/appID/DomainOrDeviceType/EntityID", so can split on "/" to get entity_id:
   String entityId = dev.deviceNetworkId.tokenize("/")[3]
   String domain = entityId.tokenize(".")[0]
   Map svcData = [entity_id: entityId, transition: 4, brightness_step: (direction == "up") ? 255 : -255]
   Map cmd = [type: "call_service", service: "turn_on", domain: domain, service_data: svcData]
   sendCommand(cmd)
}

void componentStopLevelChange(com.hubitat.app.DeviceWrapper dev) {
   if (logEnable) log.debug "componentStopLevelChange(${dev.displayName})"
   // DNI in format "Hc/appID/DomainOrDeviceType/EntityID", so can split on "/" to get entity_id:
   String entityId = dev.deviceNetworkId.tokenize("/")[3]
   String domain = entityId.tokenize(".")[0]
   // TODO: This doesn't work well on most (all?) bulbs, but HASS doesn't appear to have a standard way to really do this...
   Map svcData = [entity_id: entityId, brightness_step: 0]
   Map cmd = [type: "call_service", service: "turn_on", domain: domain, service_data: svcData]
   sendCommand(cmd)
}

void componentSetColorTemperature(com.hubitat.app.DeviceWrapper dev, Number colorTemperature, Number level=null, Number transitionTime=null) {
   if (logEnable) log.debug "componentSetColorTemperature(${dev.displayName}, $colorTemperature, $level, $transitionTime)"
   // DNI in format "Hc/appID/DomainOrDeviceType/EntityID", so can split on "/" to get entity_id:
   String entityId = dev.deviceNetworkId.tokenize("/")[3]
   String domain = entityId.tokenize(".")[0]
   Map svcData = [entity_id: entityId, kelvin: colorTemperature as Integer]
   if (level != null) svcData << [brightness_pct: level as Integer]
   if (transitionTime != null) svcData << [transition: transitionTime]
   Map cmd = [type: "call_service", service: "turn_on", domain: domain, service_data: svcData]
   sendCommand(cmd)
}

void componentSetColor(com.hubitat.app.DeviceWrapper dev, Map colorMap) {
   if (logEnable) log.debug "componentSetColor(${dev.displayName}, $colorMap)"
   // DNI in format "Hc/appID/DomainOrDeviceType/EntityID", so can split on "/" to get entity_id:
   String entityId = dev.deviceNetworkId.tokenize("/")[3]
   String domain = entityId.tokenize(".")[0]
   Map svcData = [entity_id: entityId, hs_color: [Math.round(colorMap.hue * 3.6) as Integer, colorMap.saturation]]
   if (colorMap.level != null) svcData << [brightness: colorMap.level]
   if (colorMap.rate != null) svcData << [transition: colorMap.rate]
   Map cmd = [type: "call_service", service: "turn_on", domain: domain, service_data: svcData]
   sendCommand(cmd)
}

void componentSetHue(com.hubitat.app.DeviceWrapper dev, Number hue) {
   if (logEnable) log.debug "componentSetHue(${dev.displayName}, $hue)"
   // DNI in format "Hc/appID/DomainOrDeviceType/EntityID", so can split on "/" to get entity_id:
   String entityId = dev.deviceNetworkId.tokenize("/")[3]
   String domain = entityId.tokenize(".")[0]
   Map svcData = [entity_id: entityId, hs_color: [Math.round(hue*3.6), dev.currentValue('saturation')]]
   Map cmd = [type: "call_service", service: "turn_on", domain: domain, service_data: svcData]
   sendCommand(cmd)
}

void componentSetSaturation(com.hubitat.app.DeviceWrapper dev, Number sat) {
   if (logEnable) log.debug "componentSetSaturation(${dev.displayName}, $sat)"
   // DNI in format "Hc/appID/DomainOrDeviceType/EntityID", so can split on "/" to get entity_id:
   String entityId = dev.deviceNetworkId.tokenize("/")[3]
   String domain = entityId.tokenize(".")[0]
   Map svcData = [entity_id: entityId, hs_color: [dev.currentValue('hue')*3.6, sat]]
   Map cmd = [type: "call_service", service: "turn_on", domain: domain, service_data: svcData]
   sendCommand(cmd)
}

void componentSpeak(com.hubitat.app.DeviceWrapper dev, String text, Number volume, String voice, String service=null) {
   if (logEnable) log.debug "componentSpeak(${dev.displayName}, $text, $volume = null, $voice = null)"
   // DNI in format "Hc/appID/DomainOrDeviceType/EntityID", so can split on "/" to get entity_id:
   String entityId = dev.deviceNetworkId.tokenize("/")[3]
   Map cmd = [type: "call_service", service: service ?: "google_translate_say", domain: "tts", service_data: [entity_id: entityId, message: text]]
   sendCommand(cmd)
}