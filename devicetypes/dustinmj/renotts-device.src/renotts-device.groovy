/**
 *  RenoTTS Device
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
preferences {
	section("Basic Settings"){
            input(name: "pref_voice", type: "enum", title: "Voice:", defaultValue:"Joanna*",
                                                                                           options:["Joanna*", "Ivy*", "Joey*", "Justin*", "Kendra*", "Kimberly*",
                                                                                                    "Salli*", "Astrid", "Amy", "Brian", "Carla", "Carmen", "Celine", "Chantal",
                                                                                                    "Conchita", "Cristiano", "Dora", "Emma", "Enrique", "Ewa", "Filiz",
                                                                                                    "Geraint", "Giorgio", "Gwyneth", "Hans", "Ines", "Jacek", "Jan", "Karl",
                                                                                                    "Liv", "Lotte", "Mads", "Maja", "Marlene", "Mathieu", "Maxim", "Miguel",
                                                                                                    "Mizuki", "Naja", "Nicole", "Raveena", "Ricardo", "Penelope", "Ruben",
                                                                                                    "Russell", "Tatyana", "Vitoria"])
            input(name: "pref_sampleRate", type: "enum", title: "Sample Rate:", options: ["8000","16000","22050"], defaultValue: "22050")
            input(name: "pref_testTxt", type: "text", title: "Test Text:", defaultValue:"Hello, my name is %name%")
      }
    section("Advanced Settings"){
        	input(name: "pref_padding", type: "enum", title: "Pad With Silence:", defaultValue:"None", options:["None","Before","After","Both"])
      }
}

metadata {
	definition (name: "Renotts Device", namespace: "dustinmj", author: "Dustin Jorge") {
		capability "Music Player"
		capability "Speech Synthesis"
		command "speak", ["string"]
		command "playTextAndResume", ["string"]
		command "playTextAndRestore", ["string"]
		command "playTrack", ["string"]
		command "playTrack", ["string", "object"]
		command "setTrack", ["string"]
		command "play"

		command "sendTestQuery"
		command "updateServices"

		// attributes
		attribute "currentAddress", "string"    // Connection address string
		attribute "status", "string"    // Connection address string
		attribute "testing", "string"    // Testing state
		attribute "checking", "string"    // Checking state
	}

	// UI tile definitions
	tiles(scale:2) {
		multiAttributeTile(name:"connector", type: "lighting", width: 6, height: 4, decoration:"flat") {
            tileAttribute ("device.status", key: "PRIMARY_CONTROL") {
				attributeState "connected", label:'Connected', decoration: "flat", backgroundColor:"#00A0DC", icon:"http://icon.renotts.com/renotts.bl.3x.png", defaultState:true
				attributeState "disconnected", label:'ERROR', decoration: "flat", backgroundColor:"#e86d13", icon:"http://icon.renotts.com/renotts.err.gw.3x.png"
            }
            tileAttribute ("device.currentAddress", key: "SECONDARY_CONTROL") {
                attributeState "currentAddress", label:'${currentValue}', icon: "st.Electronics.electronics6", defaultState:true
                attributeState "currentAddress", label:'${currentValue}', icon: "st.Electronics.electronics6", defaultState:true
            }
        }
        standardTile("testing", "device.testing", inactiveLabel: false, width: 2, height: 2, decoration:"flat") {
            state ("idle", label:'Send Test', action:"sendTestQuery", icon:"st.Entertainment.entertainment3", nextState:"testing", defaultState:true)
            state ("testing", label:'SPEAKING', icon:"st.Entertainment.entertainment3")
        }
        standardTile("checking", "device.checking", width:4, height:2, inactiveLabel:false, decoration:"flat") {
            state ("not", icon:"st.secondary.refresh", action:"updateServices", label:"Status", nextState:"is", defaultState:true)
            state ("is", icon:"st.Health & Wellness.health9", label:"CHECKING")
        }
		main "connector"
	}
}

/*********************************
********* System Methods *********
**********************************/

def parse(String description) {}// smartthings parsing is wacky, use callback methods instead

def parseServiceResponse( physicalgraph.device.HubResponse response ) {
    // response recieved, ip:port is fine
    notWaiting()
    // clear the button
    clearCheckStatus()
    // this shouldn't happen
    if( response.status != 200 ){
    	logerr("Could not translate services from server.")
    	statusDisconnected()
        return
    }
    logmsg( "Recieved service list from server." )
    // currently only interested in polly
    state.pref_path = response?.json?.polly
    // update attributes
    setAttrs()
    // we have connection and path
    statusConnected()
    return
}

def parseTTSResponse( physicalgraph.device.HubResponse response ) {
    // response recieved, ip:port is fine
    notWaiting()
    if( response?.status < 200 || response?.status >= 300 ) {
        logerr( "Reply from RenoTTS Server: ${msg?.headers?.status}. Running path update request now!" )
        // error probably caused by bad path
        updateServices()
        // clear the test button
        return createEvent(getClearTestStatus())
    }else{
    	logmsg( "Recieved response: Success." )
        // we've spoken
        clearToSpeak()
        // if we're showing bad status
        statusConnected()
        // clear the button
        clearTestStatus()
    }
}

// on initialize
def installed() {
    updateServices()
	unschedule()
	runEvery5Minutes("updateServices")
	state.installed = true
}

// on update prefs
def updated() {
	if( state.lastUpdate != null && state.lastUpdate + 1500 > now() ) return // update always called twice
	state.lastUpdate = now()
	checkPrefs()
	setAttrs()
}

// parent sync method
def sync( hexIP, hexPort, macAddr ){
	// store hex representations in case needed
	state.hexPort = hexPort
	state.hexIp = hexIP
	state.macAddr = macAddr
	state.dni = macAddr

	// translate data for prefs
	state.pref_ip = convertHexToIP( hexIP )
	state.pref_port = convertHexToInt( hexPort )

	// parse preferences into state
	checkPrefs()
	setAttrs()

	if( state.installed ){
		updateServices()
	}

	// see if we have a stored speaking state
	if( state.toSpeak && state.toSpeak.length() > 0 ) {
		// if we can't update in 10 seconds, just give up
		if( now() < state.toSpeakWhen + 60000 ){
		  	reSpeak(state.toSpeak)// self clear
		  }
	}
}

/*********************************
******   handle commands   *******
*********************************/

// speech synthesis hook
def speak( String txt) {
	logmsg( "Speaking ${txt}" )
	sendOff()
	// while parent is updating, I will still fail... handle that with
	// state.toSpeak and state.toSpeakWhen variable, when set, will
	// re-speak on sync. toSpeakWhen is to make sure we didn't just
	// speak, if we spoke in the last 10 seconds, we just silently ignore
	// state.toSpeak
	state.toSpeak = txt
	state.toSpeakWhen = now()
	// only keep this state for 60 seconds max
	runIn(60,"clearToSpeak")
    sendHubCommand( getTTSRequest( txt ) )
}

// music player hooks
def play() {
	if( state.toSpeakTrack != null )
    {
		speak( state.toSpeakTrack )
        state.toSpeakTrack = null
    }
}

def setTrack( txt ) {
	state.toSpeakTrack = txt
}

def playTrack( String txt ) {
	speak( txt )
}

def playTrack( String txt, Object o ) {
	speak( txt )
}

def playTrackAndRestore( String txt ) {
	speak( txt )
}

def playTrackAndResume( String txt ) {
	speak( txt )
}

/*********************************
************* public  ************
*********************************/

def sendTestQuery() {
	logmsg( "Sending test query to ${me()}" )
    sendOff()
    def txt = state.pref_testTxt == null ? "Hello I'm ${state.pref_voice}" : state.pref_testTxt
    sendHubCommand( getTTSRequest( txt ) )
}

def updateServices() {
	// case: preinstall sync
	if( state.pref_ip == null || state.pref_port == null ) return
	logmsg("Requesting service list from ${me()}" )
    sendOff()
    sendHubCommand(getAction([
        method: "GET",
        path: "/services/",
        headers: [
            HOST: state.pref_ip + ":" + state.pref_port,
            "Accept":"application/json"
        ]
    ], "parseServiceResponse"))
}

/*********************************
************ Private *************
*********************************/

// called to prevent toSpeak loop, we only retry once
private reSpeak( String txt ){
	sendHubCommand( getTTSRequest(txt) )
    // this is our hail mary
    clearToSpeak()
}

private physicalgraph.device.HubAction getTTSRequest( String txt ) {

     return getAction([
            method: "POST",
            path: state.pref_path,
            headers: [
                HOST: "${state.pref_ip}:${state.pref_port}",
                'Content-Type' : 'application/json'
            ],
            body: [text: txt, voice: state.pref_voice, samplerate: state.pref_sampleRate, padding: state.pref_padding]
        ], "parseTTSResponse")
}

private getAction( params, callback ){
    return  new physicalgraph.device.HubAction( params, device.getDeviceNetworkId(), [callback:callback])
}

// sendoff for calls
private sendOff(){
	waiting()
    runIn(7, "stillWaiting")
}

// receiver for unanswered calls
private stillWaiting( who ){
	// we never unschedule this
	if( !isWaiting() ) return
    logerr("No response recived from server, running contingencies.")
	// we're either disconnected or
	// ST Hub is delaying a long time
	statusDisconnected()
	// clear buttons
	clearButtons()
	// find out what is going on
	parent.ssdpDiscover()
}

private me(){
	return "RenoTTS Server at ${state.pref_ip}:${state.pref_port}"
}

private void setAttrs() {
	def fPath = "http://${state.pref_ip}:${state.pref_port}"
	fPath += state.pref_path != null ? state.pref_path : ""
	sendEvent([name:'currentAddress', value:fPath, displayed:true, descriptionText:"Path updated."])
}

private convertHexToInt(hex) {
	return Integer.parseInt(hex,16)
}

private convertHexToIP(hex) {
	return [convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}

private logerr( String e ) {
	log.error( "${device.getDisplayName()}: ${e}" )
}

private logmsg( String e ) {
	log.info( "${device.getDisplayName()}: ${e}" )
}

/*********************************
************ CHECKS **************
*********************************/

private checkPrefs() {
    state.pref_voice = checkVoice( settings.pref_voice )
    state.pref_testTxt = checkTestTxt( settings.pref_testTxt )
    state.pref_sampleRate = checkSampleRate( settings.pref_sampleRate )
    state.pref_padding = checkPadding( settings.pref_padding )
}

private checkVoice( String voice ){
	return voice == null ? "Joanna" : voice.replaceAll("\\*","")
}

private checkPath( String path ) {
	return path == null ? "/tts/polly/" : path
}

private checkSampleRate( String sampleRate ) {
	return sampleRate == null ? "22050" : sampleRate
}

private checkTestTxt( String txt ) {
	return txt == null ? "Hello, my name is Joanna" : txt.replace("%name%",state.pref_voice)
}

private checkPadding( String padding ) {
	return padding == null ? "None" : padding
}

/*********************************
************** STATUS ************
*********************************/

private clearTestStatus(){
    sendEvent(
        name:           'testing',
        value:          'idle',
        isStateChange:  true,
        displayed:		false)
}

private clearCheckStatus(){
    sendEvent(
        name:           'checking',
        value:          'not',
        isStateChange:  true,
        displayed:		false)
}

private statusDisconnected(){
	def displayed = state.connected
	state.connected = false
    sendEvent(
    	name:           'status',
        value:          'disconnected',
        isStateChange:  true,
        displayed:      displayed)
}

private statusConnected(){
	def displayed = !state.connected
	state.connected = true
    sendEvent(
        name:           'status',
        value:          'connected',
        isStateChange:  true,
        displayed:      displayed)
}

private clearButtons(){
	clearTestStatus()
    clearCheckStatus()
}

private waiting(){
	state.waiting = true
}

private notWaiting(){
	state.waiting = false
}

private isWaiting(){
	return state.waiting
}

private clearToSpeak(){
    state.toSpeak = ""
    state.toSpeakWhen = null
}

/*********************************
****** Unused - Music Player *****
*********************************/

def setLevel() {}
def mute() {}
def previousTrack() {}
def unmute() {}
def resumeTrack() {}
def restoreTrack() {}
def pause() {}
def stop() {}
def nextTrack() {}
