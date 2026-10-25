#!/bin/bash
# Uses Diya on an Android emulator with a virtual microphone that plays real speech,
# and records what happens (logs, screenshots, PASS/FAIL per step).
OUT=emu-out; mkdir -p $OUT
PKG=com.diya.assistant
log() { echo "=== $*" | tee -a $OUT/steps.txt; }
snap() { adb exec-out screencap -p > $OUT/$1.png; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml $OUT/$1.xml >/dev/null 2>&1; }
diya_log() { adb logcat -d -s Diya:I | grep "\[" ; }
mark() { MARK=$(diya_log | wc -l); }
since() { diya_log | tail -n +$((MARK+1)); }
wait_for() {  # wait_for <regex> <seconds>
  for i in $(seq 1 $2); do since | grep -qE "$1" && return 0; sleep 1; done; return 1; }
say() { log "speaking: $1"; paplay -d vmic voice/$1.wav; }
check() {  # check <name> <regex> <seconds>
  if wait_for "$2" $3; then log "PASS $1"; else log "FAIL $1"; fi
  since | sed 's/^/      /' | tee -a $OUT/steps.txt; }
tap_id() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  xy=$(python3 -c "
import re, xml.etree.ElementTree as ET
for n in ET.parse('/tmp/ui.xml').iter('node'):
    if n.get('resource-id','').endswith(':id/$1'):
        a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds'))); print((a+c)//2,(b+d)//2); break")
  [ -n "$xy" ] && adb shell input tap $xy && log "tapped $1"; }

log "device: Android $(adb shell getprop ro.build.version.release) $(adb shell getprop ro.product.model)"
adb install -r -g Diya.apk | tee -a $OUT/steps.txt
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell settings put secure voice_recognition_service com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService 2>/dev/null
adb logcat -c
log "launch"
mark
adb shell am start -n $PKG/.MainActivity
check "service starts and wake word ready" "\[wake\] ready" 120
snap 1-ready

log "TEST 1: wake word, then a command"
mark; sleep 2
say hey_diya
check "woke on 'Hey Diya'" "\[wake\] woke" 10
sleep 1.5
say what_is_25_times_4
check "answered 25 x 4" "\[reply\].*100" 30
snap 2-after-test1

log "TEST 2: conversation mode (no wake word)"
mark; sleep 1
say tell_me_a_joke
check "answered follow-up without wake word" "\[reply\]" 30
sleep 9

log "TEST 3: one breath"
mark
say hey_diya_time_one_breath
check "one-breath command answered" "\[reply\].*It's" 30
snap 3-after-test3
sleep 9

log "TEST 4: mic button"
mark
tap_id micButton
sleep 1.5
say whats_the_time
check "mic button command answered" "\[reply\].*It's" 30
snap 4-after-mic
sleep 9

log "TEST 5: no false wake on 'Hey there'"
mark
say hey_there
sleep 6
if since | grep -q "\[wake\] woke"; then log "FAIL woke on 'Hey there'"; else log "PASS stayed asleep on 'Hey there'"; fi
since | sed 's/^/      /' | tee -a $OUT/steps.txt

log "TEST 6: US voice wake + Hinglish"
mark; sleep 2
say hey_diya_us
check "woke on US-voice 'Hey Diya'" "\[wake\] woke" 10
sleep 1.5
say kal_ka_mausam
check "Hinglish weather answered" "\[reply\]" 35
snap 6-after-test6

adb exec-out run-as $PKG cat files/last_crash.txt > $OUT/crash.txt 2>/dev/null
adb logcat -d -v time > $OUT/logcat-full.txt
grep -iE " Diya|AndroidRuntime|FATAL|RecognitionService|SpeechRecognizer|SodaSpeech|NetworkSpeech" $OUT/logcat-full.txt > $OUT/logcat-diya.txt
log "summary: $(grep -c '^=== PASS' $OUT/steps.txt) passed, $(grep -c '^=== FAIL' $OUT/steps.txt) failed"
