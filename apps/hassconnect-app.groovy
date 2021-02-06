/**
 * ===========================  HASSConnect - Home Assistant Integration =========================
 *
 *  Copyright 2021 Robert Morris
 *
 *  DESCRIPTION:
 *  Community-developed integration for importing Home Assistant devices into Hubitat. (Note: this
 *  is one direction only for devices/entities, though events and commands pass in both directions.
 *  To share Hubitat devices with Home Assistant--also unidirectional but in the opposite
 *  direction--see the unrelated custom component/MakerAPI integration by jason0x43 instead.)
 
 *  TO INSTALL:
 *  See documentation on Hubitat Community forum or README.MD file in GitHub repo
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
 *  Last modified: 2021-02-06
 * 
 *  Changelog:
 *  v0.9    - (Beta) Initial Public Release
 */ 

import groovy.transform.Field

@Field static String lastDeviceSelectorKey // cache when using/refreshing device-selection page
@Field static String lastDeviceSelectorUIName // cache when using/refreshing device-selection page


definition (
   name: "HASSConnect - Home Assistant Integration",
   namespace: "RMoRobert",
   author: "Robert Morris",
   description: "Community-created integration for importing Home Assitant devices/entities into Hubitat",
   category: "Convenience",
   installOnOpen: true,
   documentationLink: "https://community.hubitat.com/t/COMING-SOON",
   iconUrl: "",
   iconX2Url: "",
   iconX3Url: ""
)

preferences {
   page name: "pageFirstPage"
   page name: "pageIncomplete"
   page name: "pageAddHub"
   page name: "pageTestConnection"
   page name: "pageManageHub"
   page name: "pageSelectDevices"
   page name: "pageSelectLights"
   page name: "pageSelectGroups"
   page name: "pageSelectSwitches"
   page name: "pageSelectMotionSensors"
   page name: "pageSelectLabsActivators"
}

void installed() {
   log.info("Installed with settings: ${settings}")
   initialize()
}

void uninstalled() {
   log.info("Uninstalling")
   if (!(settings['deleteDevicesOnUninstall'] == false)) {
      logDebug("Deleting child devices...")
      List DNIs = getChildDevices().collect { it.deviceNetworkId }
      logDebug("  Preparing to delete devices with DNIs: $DNIs")
      DNIs.each {
         deleteChildDevice(it)
      }
   }
}

void updated() {
    log.info("updated()")
    initialize()
}

void initialize() {
   log.debug("Initializing...")
   unschedule()
   Integer disableTime = 1800
   if (enableDebug) {
      log.debug "Debug logging will be automatically disabled in ${disableTime} seconds"
      runIn(disableTime, debugOff)
   }
}

void debugOff() {
   log.warn("Disabling debug logging")
   app.updateSetting("enableDebug", [value:"false", type:"bool"])
}

def pageFirstPage() {
   if (!(state.addedHub)) {
      if (enableDebug) log.debug "Creating hub device or ensuring was already created..."
      if (settings["ipAddress"] && settings["port"] && settings["accessToken"]) {
         if (enableDebug) log.debug "All hub information present"
         com.hubitat.app.ChildDeviceWrapper dev = getChildDevice("Hc/${app.id}")
         if (dev != null) {
            if (enableDebug) log.debug "Child hub device found; not creating."
         }
         else {
            if (enableDebug) log.debug "Creating child device..."
            Map devProps = [name: """HASSConnect HASS Hub${nickname ? " - ${nickname} " : ""}"""]
            dev = addChildDevice(childNamespace, "HASSConnect Home Assistant Hub", "Hc/${app.id}", devProps)
         }
         if (dev != null) {
            if (enableDebug) log.debug "Updating child device data..."
            dev.updateSetting("ipAddress", [value: ipAddress, type: "string"])
            dev.updateSetting("port", [value: port, type: "number"])
            dev.updateSetting("accessToken", [value: accessToken, type: "string"])
            state.addedHub = true
         }
         else {
            log.warn "HASSConnect hub device not found and could not be created"
         }
      }
      else {
         if (enableDebug) log.debug "Not creating hub device because some information missing. Re-run setup."
      }
   }
   if (app.getInstallationState() == "INCOMPLETE") {
      // Shouldn't happen with installOnOpen: true, but just in case...
      dynamicPage(name: "pageIncomplete", uninstall: true, install: true) {
         section() {
            paragraph("Please press \"Done\" to install, then re-open to configure this app.")
         }
      }
   }
   else {
      if (settings["ipAddress"] && settings["port"] && settings["accessToken"] && state.addedHub) {
         return pageManageHub()
      }
      else {
         return pageAddHub()
      }
   }
}

def pageAddHub() {
   logDebug("pageAddHub()...")
   state.addedHub = false
   dynamicPage(name: "pageAddHub", uninstall: true, install: false, nextPage: "pageFirstPage") {
      section("Connect to Home Assistant") {
         input name: "nickname", type: "text", title: "\"Nickname\" for Home Assistant hub (optional; will be used as part of app and hub device names):"
         input name: "ipAddress", type: "string", title: "IP address", description: "Example: 192.168.0.10",
            required: true
         input name: "port", type: "number", title: "Port", description: "Default: 8123", defaultValue: 8123,
            required: true
         input name: "accessToken", type: "string", title: "Access token", required: true
         paragraph "The \"long-lived access token\" required above can be created in your Home Assistant setup at: http://IP_ADDRESS:PORT/profile"
      }
      section("Test connection") {
         href name: "hrefTestConnection", title: "Test connection",
            description: "Test communication between Hubitat and Home Assistant (recommended to test after configuring the above)", page: "pageTestConnection"
      }
      section(styleSection("Logging")) {
         input name: "enableDebug", type: "bool", title: "Enable debug logging (for app)", submitOnChange: true
      }
   }
}

def pageTestConnection() {
   dynamicPage(name: "pageTestConnection", uninstall: false, install: false, nextPage: "pageFirstPage") {
      section(styleSection("Test connection")) {\
         if (testConnection()) {
            paragraph "<b>Connection succesful!</b>"
         }
         else {
            paragraph "<b>Connection failed.</b> Verify IP address, port, and key; see \"Logs\" for more details."
         }
      }
   }
}

/**
 * Method of testing connection to Home Assistant. Waits for response (synchronus).
 */
Boolean testConnection(Integer timeoutInSeconds=10) {
   if (enableDebug) log.debug "Testing Home Assistant API endpoint.."
   Boolean successful = false
   Map params = [uri: "http://${ipAddress}:${port}/api/", contentType: "application/json",
                 headers: [Authorization: "Bearer ${accessToken}"],
                 timeout: timeoutInSeconds]
   log.trace params
   try {
      httpGet(params) { response ->
         successful = (response?.status ? (response.status == 200 | response.status == 201) : false)
      }
   }
   catch (Exception ex) {
      log.error "Error when attempting to test connection: $ex"
   }
   if (enableDebug) log.debug "Connection test was ${successful ? '' : 'not '}successful"
   return successful
}

/**
 * Adds new devices if any were selected on selection pages (called when navigating back to main "manage" page)
 */
def createNewSelectedDevices() {
   // Add new devices if any were selected
   com.hubitat.app.ChildDeviceWrapper hubDev = getChildDevice("Hc/${app.id}")
   Map<String,Map<String,Object>> deviceSelectors
   if (hubDev != null) {
      deviceSelectors = hubDev.getDeviceSelectors()
      deviceSelectors?.each { selector ->
         if (settings["new${selector.key}Devices"]) {
            Map<String,String> devCache = hubDev.getCache(selector.key)
            (settings["new${it.key}Devices"]).each { selectedEntId ->
               String name = devCache.get(selectedEntId)
               if (name) {
                  try {
                     logDebug("Creating new device for HASS ${selector.key} device ${selectedEntId} (${name})")
                     String devDNI = "Hc/${app.id}/${selector.key}/${selectedEntId}"
                     Map<String,String> devProps = [name: name]
                     hubDev.addChildDevice(selector.value.driverNamespace, selector.value.driver, devDNI, devProps)
                  }
                  catch (Exception ex) {
                     log.error "Unable to create new device for $selectedEntId: $ex"
                  }
               }
               else {
                  log.error "Unable to create new device for $selectedEntId: entity ID not found in HASS cache"
               }
            }
            app.removeSetting("new${it.key}Devices")
         }
      }
   }
   else {
      log.warn "Home Assistant hub device not found!"
   }

}

def pageManageHub() {
   createNewSelectedDevices() // if any selected (since this page is the nextPage for the device-selection page)
   // Clean up after device discovery
   com.hubitat.app.ChildDeviceWrapper hubDev = getChildDevice("Hc/${app.id}")
   Map<String,Map<String,Object>> deviceSelectors
   if (hubDev != null) {
      deviceSelectors = hubDev.getDeviceSelectors()
      deviceSelectors?.each {
         hubDev.clearCache(it.key)
      }
   }
   else {
      log.warn "Home Assistant hub device not found!"
   }

   dynamicPage(name: "pageManageHub", uninstall: true, install: true) {
      section("Import Home Assitant Devices") {
         deviceSelectors.each {
            href(name: "hrefPageSelectDevices",
               page: "pageSelectDevices",
               title: "Select ${it.value.uiName}",
               description: "",
               params: [selectorKey: it.key, selectorUIName: it.value.uiName]
              )
         }
      }
      section("Other Options") {
         href name: "hrefReAddHub", title: "Edit hub IP, port, or access token",
               description: "", page: "pageAddHub"
         input name: "deleteDevicesOnUninstall", type: "bool", title: "Delete devices created by app (Bridge, light, group, and scene) if uninstalled", defaultValue: true
         input name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true
      }
   }
}

def pageSelectDevices(params) {
   if (params) {
      lastDeviceSelectorKey = params.selectorKey
      lastDeviceSelectorUIName = params.selectorUIName
   } else {
      params = [selectorKey: lastDeviceSelectorKey, selectorUIName: lastDeviceSelectorUIName]
   }
   String selctorKey = params.selectorKey
   String uiName;
   com.hubitat.app.ChildDeviceWrapper hubDev = getChildDevice("Hc/${app.id}")
   hubDev.fetchDevices(params.selectorKey)
   List arrNewDevs = []
   Map devCache = hubDev.getCache(params.selectorKey)   
   List<com.hubitat.app.ChildDeviceWrapper> unclaimedDevs = hubDev.getChildDevices().findAll { it.deviceNetworkId.startsWith("Hc/${app.id}/${params.selectorKey}/") }
   dynamicPage(name: "pageSelectDevices", refreshInterval: devCache ? 0 :5, uninstall: true, install: false, nextPage: "pageManageHub") {
      Map addedDevs = [:]  // To be populated with devices user has added, matched by HASS entity ID
      if (!hubDev) {
         log.error "No HASS hub device found"
         return
      }
      if (devCache) {
         devCache.each { cachedDev ->
            com.hubitat.app.ChildDeviceWrapper childDev = unclaimedDevs.find { s -> s.deviceNetworkId == "Hc/${app.id}/${params.key}/${cachedDev.key}" }
            if (childDev) {
               addedDevs.put(cachedDev.key, [hubitatName: childDev.name, hubitatId: childDev.id, hassName: cachedDev.value])
               unclaimedDevs.removeElement(childDev)
            } else {
               Map newDev = [:]
               newDev << [(cachedDev.key): (cachedDev.value)]
               arrNewDevs << newDev
            }
         }
         arrNewDevs = arrNewDevs.sort { a, b ->
            // Sort by friendly name name (default would be entity ID)
            a.entrySet().iterator().next()?.value <=> b.entrySet().iterator().next()?.value
         }
         addedDevs = addedDevs.sort { it.value.hubitatName }
      }
      if (!devCache) {
         section("Discovering ${params.selectorUIName}. Please wait...") {
            paragraph("Press \"Refresh\" if you see this message for an extended period of time")
            input(name: "btnDeviceRefresh", type: "button", title: "Refresh", submitOnChange: true)
         }
      }
      else {
         section("Manage ${params.selectorUIName}") {
            input(name: "new${params.selectorKey}Devices", type: "enum", title: "Select ${params.selectorUIName} to add:",
                  multiple: true, options: arrNewDevs)
            paragraph ""
            paragraph("Previously added ${params.selectorUIName}${addedDevs ? ' <span style=\"font-style: italic\">(Home Assistant name in parentheses)</span>' : ''}:")
            if (addedDevs) {
               StringBuilder sbDevInfoText = new StringBuilder()
               sbDevInfoText << "<ul>"
               addedDevs.each {
                  sbDevInfoText << "<li><a href=\"/device/edit/${it.value.hubitatId}\" target=\"_blank\">${it.value.hubitatName}</a>"
                  sbDevInfoText << " <span style=\"font-style: italic\">(${it.value.hassName ?: 'not found on Home Assistant'})</span></li>"
                  //input(name: "btnRemove_Device_ID", type: "button", title: "Remove", width: 3)
               }
               sbDevInfoText << "</ul>"
               paragraph(sbDevInfoText.toString())
            }
            else {
               paragraph "<span style=\"font-style: italic\">No added ${params.selectorUIName} found</span>"
            }
            if (unclaimedDevs) {
               paragraph "Hubitat ${params.selectorUIName} not found on Home Assistant:"
               StringBuilder sbDevInfoText = new StringBuilder()
               sbDevInfoText << "<ul>"
               unclaimedDevs.each {
                  sbDevInfoText << "<li><a href=\"/device/edit/${it.id}\" target=\"_blank\">${it.displayName}</a></li>"
               }
               sbDevInfoText << "</ul>"
               paragraph(sbDevInfoText.toString())
            }
         }
         section("Rediscover ${params.selectorUIName}") {
               paragraph("If you added new ${params.selectorUIName} to Home Assistant and do not see them above, click/tap the button " +
                        "below to retrieve new information from Home Assistant.")
               input(name: "btnDeviceRefresh", type: "button", title: "Refresh Device List", submitOnChange: true)
         }
      }
   }
}


def pageSelectSwitches() {
   com.hubitat.app.ChildDeviceWrapper hubDev = getChildDevice("Hc/${app.id}")
   hubDev.getAllSwitches()
   List arrNewSwitches = []
   Map switchCache = hubDev.getAllSwitchesCache()
   List<com.hubitat.app.ChildDeviceWrapper> unclaimedSensors = hubDev.getChildDevices().findAll { it.deviceNetworkId.startsWith("Hc/${app.id}/Switch/") }
   dynamicPage(name: "pageSelectSwitches", refreshInterval: switchCache ? 0 : 6, uninstall: true, install: false, nextPage: "pageManageHub") {
      Map addedSwitches = [:]  // To be populated with switches user has added, matched by HASS entity ID
      if (!hubDev) {
         log.error "No HASS hub device found"
         return
      }
      if (switchCache) {
         switchCache.each { cachedSw ->
            com.hubitat.app.ChildDeviceWrapper swChild = unclaimedSensors.find { s -> s.deviceNetworkId == "Hc/${app.id}/Switch/${cachedSw.key}" }
            if (swChild) {
               addedSwitches.put(cachedSw.key, [hubitatName: swChild.name, hubitatId: swChild.id, hassName: cachedSw.value])
               unclaimedSensors.removeElement(swChild)
            } else {
               Map newDev = [:]
               newDev << [(cachedSw.key): (cachedSw.value)]
               arrNewSwitches << newDev
            }
         }
         arrNewSwitches = arrNewSwitches.sort { a, b ->
            // Sort by friendly name name (default would be entity ID)
            a.entrySet().iterator().next()?.value <=> b.entrySet().iterator().next()?.value
         }
         addedSwitches = addedSwitches.sort { it.value.hubitatName }
      }
      if (!switchCache) {
         section("Discovering sensors. Please wait...") {
            paragraph("Press \"Refresh\" if you see this message for an extended period of time")
            input(name: "btnSensorRefresh", type: "button", title: "Refresh", submitOnChange: true)
         }
      }
      else {
         section("Manage Switches") {
            input(name: "newSwitches", type: "enum", title: "Select switches to add:",
                  multiple: true, options: arrNewSwitches)
            paragraph ""
            paragraph("Previously added switches${addedSwitches ? ' <span style=\"font-style: italic\">(Home Assistant name in parentheses)</span>' : ''}:")
            if (addedSwitches) {
               StringBuilder swText = new StringBuilder()
               swText << "<ul>"
               addedSwitches.each {
                  swText << "<li><a href=\"/device/edit/${it.value.hubitatId}\" target=\"_blank\">${it.value.hubitatName}</a>"
                  swText << " <span style=\"font-style: italic\">(${it.value.hassName ?: 'not found on Home Assistant'})</span></li>"
                  //input(name: "btnRemove_Sensor_ID", type: "button", title: "Remove", width: 3)
               }
               swText << "</ul>"
               paragraph(swText.toString())
            }
            else {
               paragraph "<span style=\"font-style: italic\">No added switches found</span>"
            }
            if (unclaimedSensors) {
               paragraph "Hubitat switch devices not found on Home Assistant:"
               StringBuilder swText = new StringBuilder()
               swText << "<ul>"
               unclaimedSensors.each {
                  swText << "<li><a href=\"/device/edit/${it.id}\" target=\"_blank\">${it.displayName}</a></li>"
               }
               swText << "</ul>"
               paragraph(swText.toString())
            }
         }
         section("Rediscover Switches") {
               paragraph("If you added new devices to Home Assistant and do not see them above, click/tap the button " +
                        "below to retrieve new information from Home Assistant.")
               input(name: "btnDeviceRefresh", type: "button", title: "Refresh Sensor List", submitOnChange: true)
         }
      }
   }
}

def pageSelectMotionSensors() {
   com.hubitat.app.ChildDeviceWrapper hubDev = getChildDevice("Hc/${app.id}")
   hubDev.getAllMotionSensors()
   List arrNewSensors = []
   Map sensorCache = hubDev.getAllMotionSensorsCache()
   List<com.hubitat.app.ChildDeviceWrapper> unclaimedSensors = hubDev.getChildDevices().findAll { it.deviceNetworkId.startsWith("Hc/${app.id}/Motion/") }
   dynamicPage(name: "pageSelectMotionSensors", refreshInterval: sensorCache ? 0 : 6, uninstall: true, install: false, nextPage: "pageManageHub") {
      Map addedSensors = [:]  // To be populated with sensors user has added, matched by HASS entity ID
      if (!hubDev) {
         log.error "No HASS hub device found"
         return
      }
      if (sensorCache) {
         sensorCache.each { cachedSensor ->
            //log.warn "* cached sensor = $cachedSensor"
            com.hubitat.app.ChildDeviceWrapper sensorChild = unclaimedSensors.find { s -> s.deviceNetworkId == "Hc/${app.id}/Motion/${cachedSensor.key}" }
            if (sensorChild) {
               addedSensors.put(cachedSensor.key, [hubitatName: sensorChild.name, hubitatId: sensorChild.id, hassName: cachedSensor.value])
               unclaimedSensors.removeElement(sensorChild)
            } else {
               Map newSensor = [:]
               newSensor << [(cachedSensor.key): (cachedSensor.value)]
               arrNewSensors << newSensor
            }
         }
         arrNewSensors = arrNewSensors.sort { a, b ->
            // Sort by sensor name (default would be entity ID)
            a.entrySet().iterator().next()?.value <=> b.entrySet().iterator().next()?.value
         }
         addedSensors = addedSensors.sort { it.value.hubitatName }
      }
      if (!sensorCache) {
         section("Discovering sensors. Please wait...") {
            paragraph("Press \"Refresh\" if you see this message for an extended period of time")
            input(name: "btnSensorRefresh", type: "button", title: "Refresh", submitOnChange: true)
         }
      }
      else {
         section("Manage Motion Sensors") {
            input(name: "newMotionSensors", type: "enum", title: "Select motion sensors to add:",
                  multiple: true, options: arrNewSensors)
            paragraph ""
            paragraph("Previously added sensors${addedSensors ? ' <span style=\"font-style: italic\">(Home Assistant name in parentheses)</span>' : ''}:")
            if (addedSensors) {
               StringBuilder sensorText = new StringBuilder()
               sensorText << "<ul>"
               addedSensors.each {
                  sensorText << "<li><a href=\"/device/edit/${it.value.hubitatId}\" target=\"_blank\">${it.value.hubitatName}</a>"
                  sensorText << " <span style=\"font-style: italic\">(${it.value.hassName ?: 'not found on Home Assistant'})</span></li>"
                  //input(name: "btnRemove_Sensor_ID", type: "button", title: "Remove", width: 3)
               }
               sensorText << "</ul>"
               paragraph(sensorText.toString())
            }
            else {
               paragraph "<span style=\"font-style: italic\">No added sensors found</span>"
            }
            if (unclaimedSensors) {
               paragraph "Hubitat sensor devices not found on Home Assistant:"
               StringBuilder sensorText = new StringBuilder()
               sensorText << "<ul>"
               unclaimedSensors.each {
                  sensorText << "<li><a href=\"/device/edit/${it.id}\" target=\"_blank\">${it.displayName}</a></li>"
               }
               sensorText << "</ul>"
               paragraph(sensorText.toString())
            }
         }
         section("Rediscover Sensors") {
               paragraph("If you added new sensors to Home Assistant and do not see them above, click/tap the button " +
                        "below to retrieve new information from Home Assistant.")
               input(name: "btnSensorRefresh", type: "button", title: "Refresh Sensor List", submitOnChange: true)
         }
      }
   }
}

def pageSelectLabsActivators() {
   com.hubitat.app.ChildDeviceWrapper bridge = getChildDevice("CCH/${state.bridgeID}")
   bridge.getAllLabsDevices()
   List arrNewLabsDevs = []
   Map labsCache = bridge.getAllLabsSensorsCache()
   List<com.hubitat.app.ChildDeviceWrapper> unclaimedLabsDevs = getChildDevices().findAll { it.deviceNetworkId.startsWith("CCH/${state.bridgeID}/SensorRL/") }
   dynamicPage(name: "pageSelectLabsActivators", refreshInterval: labsCache ? 0 : 6, uninstall: true, install: false, nextPage: "pageManageBridge") {
      Map addedLabsDevs = [:]  // To be populated with lights user has added, matched by Hue ID
      if (!bridge) {
         log.error "No Bridge device found"
         return
      }
      if (labsCache) {
         labsCache.each { cachedLabDev ->
            com.hubitat.app.ChildDeviceWrapper labsChild = unclaimedLabsDevs.find { d -> d.deviceNetworkId == "CCH/${state.bridgeID}/SensorRL/${cachedLabDev.key}" }
            if (labsChild) {
               addedLabsDevs.put(cachedLabDev.key, [hubitatName: labsChild.name, hubitatId: labsChild.id, hueName: cachedLabDev.value?.name])
               unclaimedLabsDevs.removeElement(labsChild)
            } else {
               Map newLabsDev = [:]
               newLabsDev << [(cachedLabDev.key): (cachedLabDev.value.name)]
               arrNewLabsDevs << newLabsDev
            }
         }
         arrNewLabsDevs = arrNewLabsDevs.sort { a, b ->
            // Sort by device name (default would be Hue ID)
            a.entrySet().iterator().next()?.value <=> b.entrySet().iterator().next()?.value
         }
         addedLabsDevs = addedLabsDevs.sort { it.value.hubitatName }
      }
      if (!labsCache) {
         section("Discovering Hue Labs activators. Please wait...") {            
            paragraph("Press \"Refresh\" if you see this message for an extended period of time")
            input(name: "btnLabsRefresh", type: "button", title: "Refresh", submitOnChange: true)
         }
      }
      else {
         section("Manage Hue Labs Formula Activators") {
            input(name: "newLabsDevs", type: "enum", title: "Select Hue Labs formula acvivators to add:",
                  multiple: true, options: arrNewLabsDevs)
            input(name: "boolAppendLabs", type: "bool", title: "Append \"(Hue Labs Formula)\" to Hubitat device name")
            paragraph ""
            paragraph("Previously added devices${addedLabsDevs ? ' <span style=\"font-style: italic\">(Hue Labs formula name on Bridge in parentheses)</span>' : ''}:")
            if (addedLabsDevs) {
               StringBuilder labDevsText = new StringBuilder()
               labDevsText << "<ul>"
               addedLabsDevs.each {
                  labDevsText << "<li><a href=\"/device/edit/${it.value.hubitatId}\" target=\"_blank\">${it.value.hubitatName}</a>"
                  labDevsText << " <span style=\"font-style: italic\">(${it.value.hueName ?: 'not found on Hue'})</span></li>"
                  //input(name: "btnRemove_LabsDev_ID", type: "button", title: "Remove", width: 3)
               }
               labDevsText << "</ul>"
               paragraph(labDevsText.toString())
            }
            else {
               paragraph "<span style=\"font-style: italic\">No added Hue Labs Forumla devices found</span>"
            }
            if (unclaimedLabsDevs) {                  
               paragraph "Hubitat devices not found on Hue:"
               StringBuilder labDevsText = new StringBuilder()
               labDevsText << "<ul>"
               unclaimedLabsDevs.each {                  
                  labDevsText << "<li><a href=\"/device/edit/${it.id}\" target=\"_blank\">${it.displayName}</a></li>"
               }
               labDevsText << "</ul>"
               paragraph(labDevsText.toString())
            }
         }
         section("Rediscover Labs Devices") {
               paragraph("If you added new Labs formulas to the Hue Bridge and do not see them above, click/tap the button " +
                        "below to retrieve new information from the Bridge.")
               input(name: "btnLabsRefresh", type: "button", title: "Refresh Labs Formula List", submitOnChange: true)
         }
      }
   }
}

String styleSection(String sectionTitle) {
   return """<span style="font-weight: bold; font-size: 110%">$sectionTitle</span>"""
}

/** Creates new Hubitat devices for new user-selected switches on switch-selection
 * page (intended to be called after navigating away/using "Done" from that page)
 */
void createNewSelectedSwitchDevices() {
   com.hubitat.app.ChildDeviceWrapper hubDev = getChildDevice("Hc/${app.id}")
   if (hubDev == null) log.error("Unable to find Home Assistant hub device")
   Map devCache = hubDev?.getAllSwitchesCache()
   settings["newSwitches"].each {
      String name = devCache.get(it)
      if (name) {
         try {
            logDebug("Creating new device for HASS switch ${it} (${name})")
            String devDNI = "Hc/${app.id}/Switch/${it}"
            Map devProps = [name: name]
            hubDev.addChildDevice("hubitat", "Generic Component Switch", devDNI, devProps)
         }
         catch (Exception ex) {
            log.error("Unable to create new device for $it: $ex")
         }
      } else {
         log.error("Unable to create new device for $it: entity ID not found in HASS cache")
      }
   }    
   hubDev.clearSwitchesCache()
   app.removeSetting("newSwitches")
}

void appButtonHandler(String btn) {
   switch(btn) {
      //case "btnName":
      //   break
      default:
         log.warn "Unhandled app button press: $btn"
   }
}

private void logDebug(str) {
   if (settings["enableDebug"] != false) log.debug(str)
}