/**
 *  RenoTTS Connector
 *
 *  Copyright 2017 Dustin M Jorge
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
 */
definition(
    name: "RenoTTS Connector",
    namespace: "Dustinmj",
    author: "Dustin M Jorge",
    description: "Discover/Connect functionality for RenoTTS server implementations.",
    category: "Fun & Social",
    iconUrl: "https://icon.renotts.com/renotts.bl.2x.png",
    iconX2Url: "https://icon.renotts.com/renotts.bl.2x.png",
    iconX3Url: "https://icon.renotts.com/renotts.bl.3x.png")

preferences {
	page(name: "deviceDiscovery", title: "RenoTTS Device Setup", content: "deviceDiscovery")
}

def deviceDiscovery() {
	def options = [:]
	def devices = getVerifiedDevices()
	devices.each {
		def value = it.value.name ?: "UPnP Device ${it.value.ssdpUSN.split(':')[1][-3..-1]}"
		def key = it.value.mac
		options["${key}"] = value
	}
	ssdpSubscribe()
    // smarthings doesn't unregister the refresh interval when
    // submitOnChange happens, page is reloaded and the dynamic page
    // will refresh multiple times every 4 seconds, to prevent
    // blasting ssdp requests, we just schedule discovery when
    // a device has already been requested... we should have
    // sent a few discoveries prior to that anyway, so no big deal
    // ... without submitOnChange, options are lost on page refresh,
    // must be selected again.
    if( !selectedDevices ) {
    	ssdpDiscover()
    }else
    {
    	unschedule("ssdpDiscover")
		runEvery1Minute("ssdpDiscover")
    }
	verifyDevices()
	return dynamicPage(name: "deviceDiscovery", title: "Discovery Started!", nextPage: "install", refreshInterval: 2, install: true, uninstall: true) {
		section("Please wait while we discover your RenoTTS Device(s). Discovery can take a bit, so sit back and relax! Select your device(s) below once discovered.") {
			input "selectedDevices", "enum", required: true, title: "Select Devices (${options.size() ?: 0} found)", multiple: true, options: options, submitOnChange: true
		}
    	remove("Remove All")
	}
}

def installed() {
	initialize()
}

def updated() {
	initialize()
}

def initialize() {
	unsubscribe()
	unschedule("ssdpDiscover")
	ssdpSubscribe()
	if (selectedDevices) {
		addDevices()
	}
	runEvery5Minutes("ssdpDiscover")
}

void ssdpDiscover() {
	// non-standard schema prevents unnecessary responses from random devices
	sendHubCommand(new physicalgraph.device.HubAction("lan discovery urn:schemas-dustinjorge-com:device:TTSEngine:1", physicalgraph.device.Protocol.LAN))
}

void ssdpSubscribe() {
	subscribe(location, "ssdpTerm.urn:schemas-dustinjorge-com:device:TTSEngine:1", ssdpHandler)
}

Map verifiedDevices() {
	def devices = getVerifiedDevices()
	def map = [:]
	devices.each {
		def value = it.value.name ?: "RenoTTS Device ${it.value.ssdpUSN.split(':')[1][-3..-1]}"
		def key = it.value.mac
		map["${key}"] = value
	}
	map
}

void verifyDevices() {
	def devices = getDevices().findAll { it?.value?.verified != true }
	devices.each {
    	verifyDevice( it )
	}
}

void verifyDevice( device )
{
    int port = convertHexToInt(device.value.deviceAddress)
    String ip = convertHexToIP(device.value.networkAddress)
    String host = "${ip}:${port}"
    sendHubCommand(new physicalgraph.device.HubAction("""GET ${device.value.ssdpPath} HTTP/1.1\r\nHOST: $host\r\n\r\n""", physicalgraph.device.Protocol.LAN, host, [callback: deviceDescriptionHandler]))
}

def getVerifiedDevices() {
	getDevices().findAll{ it.value.verified == true }
}

def getDevices() {
	if (!state.devices) {
		state.devices = [:]
	}
	state.devices
}

def addDevices() {
	def devices = getDevices()
	selectedDevices.each { dni ->
		def selectedDevice = devices.find { it.value.mac == dni }
		def d
		if (selectedDevice) {
			d = getChildDevices()?.find {
				it.deviceNetworkId == selectedDevice.value.mac
			}
		}
		if (!d) {
			log.info( "Creating RenoTTS connector with: ${selectedDevice?.value?.mac}" )
			def child = addChildDevice("dustinmj", "RenoTTS Device", selectedDevice?.value?.mac, selectedDevice?.value?.hub, [
				"label": selectedDevice?.value?.name ?: "RenoTTS Device"])
            child?.sync( selectedDevice?.value?.networkAddress, selectedDevice?.value?.deviceAddress, selectedDevice?.value?.mac )
		}
	}
}

def ssdpNTHandler( evt ) {
	def parsedEvent = parseLanMessage( evt.description )
	if( devices."${ssdpUSN}" ){
    	def d = devices."${ssdpUSN}"
        def child = getChildDevice(parsedEvent.mac)
        child?.updateServices()
    }
}

def ssdpHandler(evt) {
	def description = evt.description
	def hub = evt?.hubId
	def parsedEvent = parseLanMessage(description)
	parsedEvent << ["hub":hub]
	def devices = getDevices()
	String ssdpUSN = parsedEvent.ssdpUSN.toString()
   	log.info( "Received ssdp broadcast from: ${parsedEvent?.networkAddress} with mac address ${parsedEvent?.mac}." );
	if (devices."${ssdpUSN}") {
		def d = devices."${ssdpUSN}"
        // sync can be lost, so we sync on every ssdp match
		def child = getChildDevice(parsedEvent.mac)
        log.info( "Updating child ${parsedEvent.mac} with new data: ${parsedEvent.networkAddress}, ${parsedEvent.deviceAddress}")
		child?.sync( parsedEvent.networkAddress, parsedEvent.deviceAddress, parsedEvent.mac )
		if (d.networkAddress != parsedEvent.networkAddress || d.deviceAddress != parsedEvent.deviceAddress) {
			d.networkAddress = parsedEvent.networkAddress
			d.deviceAddress = parsedEvent.deviceAddress
		}
	} else{
		devices << ["${ssdpUSN}": parsedEvent]
	}
}

void deviceDescriptionHandler(physicalgraph.device.HubResponse hubResponse) {
	def body = hubResponse.xml
	def devices = getDevices()
	def device = devices.find { it?.key?.contains(body?.device?.UDN?.text()) }
	if (device) {
		device.value << [name: body?.device?.friendlyName?.text(), model:body?.device?.modelName?.text(), verified: true]
	}
}

private Integer convertHexToInt(hex) {
	Integer.parseInt(hex,16)
}

private String convertHexToIP(hex) {
	[convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}
