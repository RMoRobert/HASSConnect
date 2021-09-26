/**
 * ============================  HASSConnect Home Assistant Button (Driver) =============================
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
 *  Last modified: 2021-09-26
 *
 *  Changelog:
 *  v0.9    - (Beta) Initial public release
 */


import groovy.transform.Field

// format: [deviceId: [lastCommand, lastCommandArgs]]
@Field static java.util.concurrent.ConcurrentHashMap<Long,List> lastCommand

@Field static final Map<String,Object> deviceProfiles = [
   "EcoSmart 4-Button Remote (Adurolight 81825)": [
      numberOfButtons: 4,
      commands:
         [
            // map with command (from ZHA) as key and closure returning List in [buttonNumber, buttonEvent] format
            // with possibly "optional" Hubiat device ID and ZHA arguments (from ZHA event data) passed in
            "on":                { Long deviceId=null, List<Integer> args = [] -> return [1, "pushed"] },
            "off":               { Long deviceId=null, List<Integer> args = [] -> return [1, "pushed"] },
            "move_to_level":     { Long deviceId=null, List<Integer> args = [] -> return [2, "pushed"] },
            "move":              { Long deviceId=null, List<Integer> args = [] -> return [2, "held"] },
            "stop":              { Long deviceId=null, List<Integer> args = [] ->  return [2, "released"] },
            "move_to_color_temp": { Long deviceId, List<Integer> args ->
               if (!lastCommand[deviceId] || lastCommand[deviceId][0] != "move_to_level_with_on_off") {
                  return [3, "pushed"]
               }
               else {
                  return []
               }
            },
            "move_color_temp": { Long deviceId=null, List<Integer> args = [] -> 
               if (args[0] != 0) {
                  return [3, "held"]
               }
               else {
                  return [3, "released"]
               }
            },
            "move_to_level_with_on_off": { Long deviceId=null, List<Integer> args = [] -> return [4, "pushed"] }
      ]
   ],
   "Generic 2-Button On/Off": [
      numberOfButtons: 2,
      commands:
         [
            "on": { Long deviceId=null, List<Integer> args = [] -> return [1, "pushed"] },
            "off": { Long deviceId=null, List<Integer> args = [] -> return [2, "pushed"] }
      ]
   ],
   "Hue Dimmer (v1)": [
      numberOfButtons: 4,
      commands:
         [
            "on_short_release": { Long deviceId=null, Map args = [:] -> return [1, "pushed"] },
            "on_hold": { Long deviceId=null, Map args = [:] -> return [1, "held"] },
            "on_long_release": { Long deviceId=null, Map args = [:] -> return [1, "released"] },
            "up_short_release": { Long deviceId=null, Map args = [:] -> return [2, "pushed"] },
            "up_hold": { Long deviceId=null, Map args = [:] -> return [2, "held"] },
            "up_long_release": { Long deviceId=null, Map args = [:] -> return [2, "released"] },
            "down_short_release": { Long deviceId=null, Map args = [:] -> return [3, "pushed"] },
            "down_hold": { Long deviceId=null, Map args = [:] -> return [3, "held"] },
            "down_long_release": { Long deviceId=null, Map args = [:] -> return [3, "released"] },
            "off_short_release": { Long deviceId=null, Map args = [:] -> return [4, "pushed"] },
            "off_hold": { Long deviceId=null, Map args = [:] -> return [4, "held"] },
            "off_long_release": { Long deviceId=null, Map args = [:] -> return [4, "released"] }
      ]
   ],
   "Hue Dimmer (v1) Fast (pushed/released)": [
      numberOfButtons: 4,
      commands:
         [
            "on": { Long deviceId=null, List args = [] -> return [1, "pushed"] },
            "on_short_release": { Long deviceId=null, Map args = [:] -> return [1, "released"] },
            "on_long_release": { Long deviceId=null, Map args = [:] -> return [1, "released"] },
            "step": { Long deviceId=null, List args = [] -> 
               if (args[0] == 0) return [2, "pushed"]
               else return [3, "pushed"]
            },
            "up_short_release": { Long deviceId=null, Map args = [:] -> return [2, "released"] },
            "up_long_release": { Long deviceId=null, Map args = [:] -> return [2, "released"] },
            "down_short_release": { Long deviceId=null, Map args = [:] -> return [3, "released"] },
            "down_long_release": { Long deviceId=null, Map args = [:] -> return [3, "released"] },
            "off": { Long deviceId=null, List args = [] -> return [4, "pushed"] },
            "off_short_release": { Long deviceId=null, Map args = [:] -> return [4, "released"] },
            "off_long_release": { Long deviceId=null, Map args = [:] -> return [4, "released"] },
      ]
   ],
   "Ikea 5-Button Remote": [
      numberOfButtons: 2,
      commands:
         [
            "toggle":                { Long deviceId=null, List<Integer> args = [] -> return [1, "pushed"] },
            "step_with_on_off":      { Long deviceId=null, List<Integer> args = [] -> return [2, "pushed"] },
            "step": { Long deviceId=null, List<Integer> args = [] -> return [3, "pushed"] },
            "stop": { Long deviceId=null, List<Integer> args = [] ->
               Integer btnNum = (lastCommand[deviceId] && lastCommand[deviceId][0] == "move_with_on_off") ? 2 : 3
               return [btnNum, "released"]
            },
            "press": { Long deviceId=null, List<Integer> args = [] ->
               Integer btnNum = (args[0] == 257) ? 4 : 5
               return [btnNum, "pushed"]
            },
            "move_with_on_off":      { Long deviceId=null, List<Integer> args = [] -> return [2, "held"] },
            "move":                  { Long deviceId=null, List<Integer> args = [] -> return [3, "held"] },
            "hold": { Long deviceId=null, List<Integer> args = [] ->
               Integer btnNum = (args[0] == 3329) ? 4 : 5
               return [btnNum, "held"]
            },
            "release": { Long deviceId=null, List<Integer> args = [] ->
               Integer btnNum
               if (lastCommand[deviceId] && lastCommand[deviceId][1][0] == 257) btnNum = 4
               else btnNum = 5
               return [btnNum, "released"]
            }
      ]
   ]
]

metadata {
   definition (name: "HASSConnect ZHA Button", namespace: "RMoRobert", author: "Robert Morris",
               importUrl: "https://raw.githubusercontent.com/RMoRobert/HASSConnect/main/drivers/hassconnect-zha-button.groovy") {
      capability "Actuator"
      capability "PushableButton"
      capability "HoldableButton"
      capability "ReleasableButton"

      //command "setNumberOfButtons", ["NUMBER"]
   }
   
   preferences() {
      input name: "deviceProfile", type: "enum", title: "Device profile (button device type)",
         options: (deviceProfiles.keySet() as ArrayList)
      input name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "enableDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true
   }
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
   if (enableDebug) {
      Integer disableTime = 1800
      log.debug "Debug logging will be automatically disabled in ${disableTime/60} minutes"
      runIn(disableTime, debugOff)
   }
   Integer numberOfButtons = 1
   if (deviceProfile && deviceProfiles.deviceProfile?.numberOfButtons) {
      numberOfButtons = deviceProfiles.deviceProfile?.numberOfButtons
   }
   if (device.currentValue("numberOfButtons") != numberOfButtons) setNumberOfButtons(numberOfButtons)
}

void debugOff() {
   log.warn "Disabling debug logging"
   device.updateSetting("enableDebug", [value:"false", type:"bool"])
}

void setNumberOfButtons(Number number) {
   if (enableDebug) log.debug "setNumberOfButtons($number)"
   doSendEvent("numberOfButtons", number, false)
}

void parse(description) {
   log.warn "not implemented: parse($description)"
}

void buttonParse(String eventType, Map eventData) {
   if (enableDebug) log.debug "buttonParse($eventType, $eventData)"
   // For now, should always be "zha_event," but in case add additional types in future...
   if (eventType == "zha_event") {
      log.trace "TO PARSE: $eventData"
      if (deviceProfiles[deviceProfile]) {
         if (lastCommand == null) lastCommand = [:]
         Closure buttonEvtCl = deviceProfiles[deviceProfile].commands[eventData.command]
         if (!buttonEvtCl) {
            if (enableDebug) log.debug "No match found; skipping"; return
         }
         List<Integer,String> parsedInfo = buttonEvtCl(device.idAsLong, eventData.args)
         //lastCommand.put(device.idAsLong, [(eventData.command): eventData.args ?: [])
         lastCommand[(device.idAsLong)] = [eventData.command, eventData.args ?: []]
         log.trace lastCommand
         if (!parsedInfo[0] || !parsedInfo[1]) {
            if (enableDebug) log.debug "Not generating event for $eventData (event name = ${parsedInfo[1]}, event value = ${parsedInfo[0]})"
         }
         else {
            doSendEvent(parsedInfo[1], parsedInfo[0], true)
         }
      }
      else {
         "Skip: unknown device profile $deviceProfile"
      }
   }
   else {
      log.warn "skipping unhandled eventType: $eventType"
   }
}

void push(Integer buttonNumber) {
   doSendEvent("pushed", buttonNumber)
}

void hold(Integer buttonNumber) {
   doSendEvent("held", buttonNumber)
}
void release(Integer buttonNumber) {
   doSendEvent("released", buttonNumber)
}

private void doSendEvent(String eventName, eventValue, Boolean forceStateChange=null) {
   //if (enableDebug) log.debug ("Creating event for $eventName...")
   String descriptionText = "${device.displayName} ${eventName} is ${eventValue}"
   if (settings.enableDesc) log.info descriptionText
   sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, isStateChange: (forceStateChange == true))
}