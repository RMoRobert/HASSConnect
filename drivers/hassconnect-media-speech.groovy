/**
 * =======================================================================================
 *  Copyright 2021-2023 Robert Morris
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
 *  Last modified: 2022-01-02
 *
 *  Changelog:
 *  v0.9.1  - (Beta) Add Alexa Media Player support
 *  v0.9    - (Beta) Initial Public Release -- message/text only, no voice or volume yet supported
 */ 
 
import groovy.transform.Field

@Field static final Integer autoDisableDebugMinutes = 30
@Field static final String defaultTTSServiceDomain = "tts"
@Field static final String defaultTTSServiceName = "speak"
@Field static final String defaultTargetEntityId = "tts.google_en_com"

metadata {
   definition (name: "HASSConnect Media/Speech Device", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/RMoRobert/HASSConnect/main/drivers/hassconnect-media-speech.groovy") {
      capability "Actuator"
      capability "Switch"
      capability "SpeechSynthesis"
      capability "Refresh"  // can comment out if don't need
   }
      
preferences {
      input name: "ttsServiceDomain", type: "string", title: "TTS service domain [default: tts]", defaultValue: defaultTTSServiceDomain
      input name: "ttsServiceName", type: "string", title: "TTS service name [default: speak]", defaultValue: defaultTTSServiceName
      input name: "targetEntityId", type: "string", title: "Target entity ID (media player entity ID will be same as device, extracted from DNI) [default: tts.google_en_com]", defaultValue: defaultTargetEntityId
      input name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "enableDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true
   }
}

void installed(){
   log.debug "Installed..."
   initialize()
}

void updated() {
   if (enableDebug) log.debug "Updated..."
   initialize()
}

void initialize() {
   if (enableDebug) log.debug "Initializing"
   if (enableDebug) {
      log.debug "Debug logging will be automatically disabled in ${autoDisableDebugMinutes} minutes"
      runIn(autoDisableDebugMinutes*60, "debugOff")
   }
}

void debugOn(Boolean autoDisable=true) {
   log.warn "Enabling debug logging..."
   if (autoDisable) {
      log.debug "Debug logging will be automatically disabled in ${autoDisableDebugMinutes} minutes"
      runIn(autoDisableDebugMinutes*60, debugOff)
   }
   device.updateSetting("enableDebug", [value:"true", type:"bool"])
}

void debugOff() {
   log.warn "Disabling debug logging"
   device.updateSetting("enableDebug", [value:"false", type:"bool"])
}

void doSendEvent(Map eventData, Boolean forceStateChange=false) {
   if (enableDebug) log.debug("doSendEvent(${eventData}...")
   String descriptionText = "${device.displayName} ${eventData.name} is ${eventData.value}${eventData.unit ?: ''}"
   if (enableDesc && (device.currentValue(eventData.name) != eventData.value || eventData.isStateChange)) log.info(descriptionText)
   Map eventProperties = [name: eventData.name, value: eventData.value, descriptionText: descriptionText,
      unit: eventData.unit, phyiscal: eventData.physical, digital: eventData.digital,
      isStateChange: eventData.isStateChange]
   if (forceStateChange) eventProperties["isStateChange"] = true
   sendEvent(eventProperties)
}

void parse(String description) {
   log.warn "parse(String description) not implemented: '${description}'"
}

void parse(List<Map> description) {
   if (enableDebug) log.debug "parse(List description: $description)"
   description.each {
       if (it.name == "switch") {
            doSendEvent(it)
       }
       else {
            log.trace "ignoring: $it"
      }
   }
}

void on() {
   parent.componentOn(this.device)
}

void off() {
   parent.componentOff(this.device)
}

void refresh() {
   if (enableDebug) log.debug "refresh()"
   parent.refresh(this.device)
}

void speak(String text, Number volume = null, String voice = null) {
   // NOTE: volume and voice are currently ignored--not yet implemented
   if (enableDebug) log.debug "speak($text, $volume, $voice)"
   String targetEntityId = settings.targetEntityId ?: defaultTargetEntityId
   parent.componentSpeak(
      this.device, text, volume, voice, settings.ttsServiceDomain, settings.ttsServiceName, targetEntityId
   )
}