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
 *  Last modified: 2021-08-22
 * 
 *  Changelog:
 *  v0.9.2  - Added preliminary button device/event support
 *  v0.9.1  - Added preliminary TTS (Chromecast) support
 *  v0.9    - (Beta) Initial Public Release
 */ 

import groovy.transform.Field

@Field static String lastDeviceSelectorKey // cache when using/refreshing device-selection page
@Field static String lastDeviceSelectorUIName // cache when using/refreshing device-selection page

@Field static final String customDriverNamespace = "RMoRobert"


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
   page name: "pageManageButtons"
   page name: "pageAddButton"
}

void installed() {
   log.trace("Installed with settings: ${settings}")
   initialize()
}

void uninstalled() {
   log.trace "uninstalled()"
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
    log.debug "updated()"
    initialize()
}

void initialize() {
   log.debug "initialize()"
   unschedule()
   Integer disableTime = 1800
   if (enableDebug) {
      log.debug "Debug logging will be automatically disabled in ${disableTime} seconds"
      runIn(disableTime, debugOff)
   }
}

void debugOff() {
   log.warn "Disabling debug logging after timeout"
   app.updateSetting("enableDebug", [value:"false", type:"bool"])
}

def pageFirstPage() {
   com.hubitat.app.ChildDeviceWrapper hubDev = getChildDevice("Hc/${app.id}")
   if (hubDev == null) {
      if (enableDebug) log.debug "Preparing to create hub device..."
      if (settings["ipAddress"] && settings["port"] && settings["accessToken"]) {
         if (enableDebug) log.debug "All hub information present"
         if (enableDebug) log.debug "Creating child device..."
         Map devProps = [name: """HASSConnect HASS Hub${nickname ? " - ${nickname} " : ""}"""]
         hubDev = addChildDevice(customDriverNamespace, "HASSConnect Home Assistant Hub", "Hc/${app.id}", devProps)
         if (hubDev != null) {
            updateHubChildSettings()            
         }
         else {
            log.error "HASSConnect hub device not found and could not be created"
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
      if (settings["ipAddress"] && settings["port"] && settings["accessToken"] && hubDev != null) {
         return pageManageHub()
      }
      else {
         return pageAddHub()
      }
   }
}

def pageAddHub() {
   logDebug("pageAddHub()...")
   com.hubitat.app.ChildDeviceWrapper hubDev = getChildDevice("Hc/${app.id}")
   dynamicPage(name: "pageAddHub", uninstall: true, install: false, nextPage: "pageFirstPage") {
      section("Connect to Home Assistant") {
         if (hubDev != null) {
            paragraph "NOTE: Hub device already detected on Hubitat. Editing the below may fail; try editing the hub device directly if any of the below fails."
         }
         input name: "nickname", type: "text", title: "\"Nickname\" for Home Assistant hub (optional; will be used as part of app and hub device names):"
         input name: "ipAddress", type: "string", title: "IP address", description: "Example: 192.168.0.10", submitOnChange: true,
            required: true
         input name: "port", type: "number", title: "Port", description: "Default: 8123", defaultValue: 8123, submitOnChange: true,
            required: true
         input name: "useSecurity", type: "bool", title: "Use TLS", submitOnChange: true
         input name: "accessToken", type: "string", title: "Long-lived access token", submitOnChange: true, required: true
         paragraph "The \"long-lived access token\" required above can be created in your Home Assistant setup at: <code>${ipAddress ?: '&lt;yourHomeAssistantIP&gt;'}/profile</code>"
         input name: "btnSaveHub", type: "button", title: "Save Hub Settings"
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
   dynamicPage(name: "pageTestConnection", uninstall: false, install: false, nextPage: "pageAddHub") {
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
void createNewSelectedDevices() {
   // Add new devices if any were selected
   com.hubitat.app.ChildDeviceWrapper hubDev = getChildDevice("Hc/${app.id}")
   Map<String,Map<String,Object>> deviceSelectors
   if (hubDev != null) {
      deviceSelectors = hubDev.getDeviceSelectors()
      deviceSelectors?.each { selector ->
         if (settings["new${selector.key}Devices"]) {
            Map<String,String> devCache = hubDev.getCache(selector.key)
            (settings["new${selector.key}Devices"]).each { selectedEntId ->
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
            app.removeSetting("new${selector.key}Devices")
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
               params: [selectorKey: it.key, selectorUIName: it.value.uiName, selectorNotes: it.value.uiNotes]
            )
         }
         href(name: "hrefPageManageButtons",
            page: "pageManageButtons",
            title: "Manage button devices",
            description: ""
         )

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
      lastDeviceSelectorNotes = params.selectorNotes
   } else {
      params = [selectorKey: lastDeviceSelectorKey, selectorUIName: lastDeviceSelectorUIName, selectorNotes: lastDeviceSelectorNotes]
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
            log.trace "cachedDev = $cachedDev"
            com.hubitat.app.ChildDeviceWrapper childDev = unclaimedDevs.find { d -> d.deviceNetworkId == "Hc/${app.id}/${params.selectorKey}/${cachedDev.key}" }
            log.trace "childDev = $childDev"
            if (childDev) {
               addedDevs.put(cachedDev.key, [hubitatName: childDev.name, hubitatId: childDev.id, hassName: cachedDev.value])
               unclaimedDevs.removeElement(childDev)
            } else {
               Map newDev = [:]
               newDev << [(cachedDev.key): (cachedDev.value)]
               arrNewDevs << newDev
            }
            log.trace "unclaimedDevs = $unclaimedDevs"
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
            if (params.selectorNotes) paragraph(params.selectorNotes)
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

def pageManageButtons() {
   com.hubitat.app.ChildDeviceWrapper hubDev = getChildDevice("Hc/${app.id}")
   if (settings.buttonDeviceIEEE) {
      if (settings.buttonDeviceIEEE) {
         if (enableDebug) log.debug "Adding button device: IEEE = ${settings.buttonDeviceIEEE}, numberOfButtons = ${settings.buttonDeviceNumberOfButtons}"
         try {
            String devDNI = "Hc/${app.id}/zha_button/${buttonDeviceIEEE}"
            Map devProps = [name: (settings.buttonDeviceName ?: "HASSConnect Button")]
            hubDev.addChildDevice("RMoRobert", "HASSConnect ZHA Button", devDNI, devProps)
         }
         catch (Exception ex) {
            log.error("Unable to create new button device: $ex")
         }
      }
      app.removeSetting "buttonDeviceName"
      app.removeSetting "buttonDeviceIEEE"
   }
   dynamicPage(name: "pageManageButtons", nextPage: "pageManageHub") {
      section(styleSection("Manage Button Devices")) {
         href name: "hrefAddButton", title: "Add button",
               description: "", page: "pageAddButton"
      }
      section(styleSection("Previously Added Button Devices")) {
         List<com.hubitat.app.ChildDeviceWrapper> buttonDevs = hubDev.getChildDevices().findAll { it.deviceNetworkId.startsWith("Hc/${app.id}/zha_button/") }
         if (buttonDevs) {
            StringBuilder sbButtonDevs = new StringBuilder()
            sbButtonDevs << "<ul>"
            buttonDevs.each {
               sbButtonDevs << "<li><a href=\"/device/edit/${it.id}\" target=\"_blank\">${it.displayName}</a></li>"
            }
            sbButtonDevs << "</ul>"
            paragraph(sbButtonDevs.toString())
         }
         else {
            paragraph "None found"
         }
      }
   }
}

def pageAddButton() {
   dynamicPage(name: "pageAddButton", nextPage: "pageManageButtons") {
      section(styleSection("Add Button Device")) {
         paragraph "To find the IEEE ID for your button device, copy/paste it from <b>Configuration > Devices > <i>(device)</i> > Zigbe Information > IEEE</b>, or go to <b>Developer Tools > Events > Listen To Events</b>, type or paste <b>zha_event</b> (or <b>*</b>) into \"Events to subscribe to,\" and click \"Start Listening,\" press a button on the device, and monitor the output."
         input name: "buttonDeviceName", title: "Device name", description: "Can be anything you want (does not need to match HASS name)", type: "string", required: true
         input name: "buttonDeviceIEEE", title: "Button device IEEE:", type: "string", required: true
         paragraph "<b>NOTE: After adding this button device,</b> open the device page and choose a device profile (under Preferences) for this button to ensure proper functionality."
         paragraph "Press \"Next\" to add."
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

void updateHubChildSettings() {
   com.hubitat.app.ChildDeviceWrapper hubDev = getChildDevice("Hc/${app.id}")
   hubDev.updateSetting("ipAddress", [value: ipAddress, type: "string"])
   hubDev.updateSetting("port", [value: port, type: "number"])
   hubDev.updateSetting("accessToken", [value: accessToken, type: "string"])
   hubDev.updateSetting("useSecurity", [value: useSecurity, type: "bool"])
   pauseExecution(2500)
   hubDev.initialize()
}

void appButtonHandler(String btn) {
   switch(btn) {
      case "btnSaveHub":
         updateHubChildSettings()
         break
      case "btnDeviceRefresh":
         // nothing, just refrehs page
         break
      default:
         log.warn "Unhandled app button press: $btn"
   }
}

private void logDebug(str) {
   if (settings["enableDebug"] != false) log.debug(str)
}