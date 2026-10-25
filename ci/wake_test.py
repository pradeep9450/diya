"""
Wake-word lab test (runs on GitHub Actions, which has internet).
Synthesises speech in several voices and runs it through EXACTLY the same two-step
pipeline as WakeWordListener.kt (streaming 0.1 s chunks, grammar detector, delay,
full-vocabulary re-check of the last N seconds, block rules from assets/wake_rules.json).
Writes results.json.
"""
import json, os, re, subprocess, sys, wave, array
from vosk import Model, KaldiRecognizer, SetLogLevel

SetLogLevel(-1)
MODEL, OUT, RULES = sys.argv[1], sys.argv[2], json.load(open(sys.argv[3]))
os.makedirs("audio", exist_ok=True)
RATE = 16000

def norm(t): return re.sub(r"\s+", " ", re.sub(r"[^a-z' ]", " ", t.lower().replace("[unk]", " "))).strip()
BLOCK = re.compile(RULES["block"])
CONFIRM = re.compile(RULES["confirm"])
GRAMMAR = [norm(g) for g in RULES["grammar"]]
def candidate(t):
    p = " " + norm(t) + " "
    return any(" " + g + " " in p for g in GRAMMAR)
def vetoed(t):
    """Mirror of WakeWord.vetoed: wake only if the check confirms a Diya-like phrase."""
    t = norm(t)
    return not CONFIRM.search(t) or bool(BLOCK.search(t))

model = Model(MODEL)
GJSON = json.dumps(GRAMMAR + ["[unk]"])

def pipeline(path):
    """Mirror of WakeWordListener.loop. Returns list of events."""
    wf = wave.open(path, "rb")
    fast = KaldiRecognizer(model, RATE, GJSON)
    check = KaldiRecognizer(model, RATE)
    ring_len = int(RATE * RULES["verifySeconds"])
    ring = array.array("h", [0] * ring_len); pos = 0; filled = 0
    samples = 0; verify_at = -1; cand = ""; events = []; heard = []
    while True:
        data = wf.readframes(RATE // 10)
        if not data: break
        chunk = array.array("h", data); n = len(chunk)
        for s in chunk:
            ring[pos] = s; pos = (pos + 1) % ring_len
        filled = min(ring_len, filled + n); samples += n
        if fast.AcceptWaveform(data):
            text = json.loads(fast.Result()).get("text", "")
        else:
            text = json.loads(fast.PartialResult()).get("partial", "")
        text = norm(text)
        if text and (not heard or heard[-1] != text): heard.append(text)
        if verify_at < 0 and candidate(text):
            cand = text; verify_at = samples + RATE * RULES["verifyDelayMs"] // 1000
        if 0 <= verify_at <= samples:
            verify_at = -1
            start = (pos - filled) % ring_len
            audio = array.array("h", (ring[(start + i) % ring_len] for i in range(filled)))
            check.Reset()
            check.AcceptWaveform(audio.tobytes())
            h = norm(json.loads(check.FinalResult()).get("text", ""))
            if vetoed(h):
                events.append({"t": round(samples / RATE, 1), "cand": cand, "check": h, "wake": False})
                fast.Reset()
            else:
                events.append({"t": round(samples / RATE, 1), "cand": cand, "check": h, "wake": True})
                fast = KaldiRecognizer(model, RATE, GJSON)  # app restarts listening after a reply
    return events, heard

def gtts(text, lang, tld, path):
    from gtts import gTTS
    gTTS(text=text, lang=lang, tld=tld).save(path + ".mp3"); return path + ".mp3"

def espeak(text, voice, path):
    subprocess.run(["espeak-ng", "-v", voice, "-w", path + ".wav", text], check=True, capture_output=True)
    return path + ".wav"

def to16k(src, dst, tempo=1.0, noise=False, pad=True):
    filt = f"atempo={tempo}" if tempo != 1.0 else "anull"
    if noise:
        cmd = ["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-f", "lavfi", "-i",
               "anoisesrc=color=pink:amplitude=0.03:sample_rate=16000", "-filter_complex",
               f"[0:a]aresample=16000,{filt}[v];[v][1:a]amix=inputs=2:duration=first:weights=1 1",
               "-ac", "1", "-ar", "16000", dst]
    else:
        cmd = ["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-af", f"aresample=16000,{filt}",
               "-ac", "1", "-ar", "16000", dst]
    subprocess.run(cmd, check=True)
    if not pad: return dst
    padded = dst.replace(".wav", "_p.wav")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", dst, "-af",
                    "adelay=600|600,apad=pad_dur=2.0", "-ac", "1", "-ar", "16000", padded], check=True)
    return padded

POSITIVE = ["Hey Diya", "Hey Diya.", "Hi Diya", "Hey Diya, what's the time?", "Hey Diya, set a timer", "Hey, Diya",
            "Hey Diya, kal ka mausam batao"]
NEGATIVE = [
    "Hey there, how are you?", "What is the idea?", "Hello dear friend", "I went to India yesterday",
    "Please pass the water", "Hey, did you eat?", "They are here now", "Good morning everyone",
    "Give me a good idea", "Hey Siri", "Hey Google", "Alexa, play music", "Light the diya for Diwali",
    "The dog is in the garden", "Can you hear me?", "Hey Priya", "Hey Riya", "Hey Dinesh",
    "Hey dude, what's up?", "Hey daddy, come here", "Hi Diana", "Hi dear, how was your day?", "Hey yaar, listen",
    "Did you see the new movie?", "Dear sir, thank you", "Hey, who is there?",
]
HINDI_POS = ["हे दिया", "हाय दिया"]
HINDI_NEG = ["आज मौसम कैसा है", "क्या हाल है", "दीया जलाओ", "हे भगवान", "अरे यार क्या कर रहा है", "हाय, कैसे हो"]
CONVERSATION = (
    "Good morning. I was thinking we should go to the market today. Hey, did you call your mother? "
    "She said the idea of a trip to India sounds great. Hello dear, the tea is ready. They are here now, "
    "please open the door. Hey there, nice to see you. What is the plan for the evening? We can watch a movie "
    "or play a game. I need to buy milk, bread and some vegetables. The weather is very hot today. "
    "Hey Google is not on this phone. Can you hear the music? Let's light the diya for Diwali tonight."
)
CONVERSATION_HI = "आज बहुत गर्मी है। चलो बाज़ार चलते हैं। अरे यार, खाना कब बनेगा? हाय, कैसे हो तुम? दीया जलाओ, आज दिवाली है। क्या हाल है भाई?"

voices = [("gtts-in", lambda t, p: gtts(t, "en", "co.in", p)), ("gtts-us", lambda t, p: gtts(t, "en", "com", p)),
          ("gtts-uk", lambda t, p: gtts(t, "en", "co.uk", p)), ("espeak-us", lambda t, p: espeak(t, "en-us", p))]
cases = []; i = 0
def add(text, positive, voice, maker, variants):
    global i
    for tempo, noise in variants:
        i += 1; base = f"audio/{i}"
        try:
            wav = to16k(maker(text, base), base + "_16k.wav", tempo, noise)
            ev, heard = pipeline(wav)
            cases.append({"text": text, "positive": positive, "voice": voice, "tempo": tempo, "noise": noise,
                          "woke": any(e["wake"] for e in ev), "events": ev, "heard": heard[-6:]})
        except Exception as e:
            cases.append({"text": text, "positive": positive, "voice": voice, "error": str(e)})

full = [(1.0, False), (0.85, False), (1.2, False), (1.0, True)]
for v, mk in voices:
    for t in POSITIVE: add(t, True, v, mk, full if v.startswith("gtts") else [(1.0, False)])
    for t in NEGATIVE: add(t, False, v, mk, [(1.0, False), (1.0, True)] if v.startswith("gtts") else [(1.0, False)])
for t in HINDI_POS: add(t, True, "gtts-hi", lambda t, p: gtts(t, "hi", "co.in", p), full)
for t in HINDI_NEG: add(t, False, "gtts-hi", lambda t, p: gtts(t, "hi", "co.in", p), [(1.0, False), (1.0, True)])

streams = []
for v, mk, text in [("gtts-in", lambda t, p: gtts(t, "en", "co.in", p), CONVERSATION),
                    ("gtts-us", lambda t, p: gtts(t, "en", "com", p), CONVERSATION),
                    ("gtts-hi", lambda t, p: gtts(t, "hi", "co.in", p), CONVERSATION_HI)]:
    i += 1; base = f"audio/{i}"
    wav = to16k(mk(text, base), base + "_16k.wav", pad=False)
    secs = wave.open(wav).getnframes() / RATE
    ev, _ = pipeline(wav)
    streams.append({"voice": v, "seconds": round(secs), "false_wakes": [e for e in ev if e["wake"]], "vetoed": [e for e in ev if not e["wake"]]})

pos = [c for c in cases if c["positive"] and "error" not in c]
neg = [c for c in cases if not c["positive"] and "error" not in c]
human = lambda c: not c["voice"].startswith("espeak")
summary = {
    "human_voices_positive": f'{sum(c["woke"] for c in pos if human(c))}/{sum(1 for c in pos if human(c))}',
    "human_voices_false": f'{sum(c["woke"] for c in neg if human(c))}/{sum(1 for c in neg if human(c))}',
    "robot_voice_positive": f'{sum(c["woke"] for c in pos if not human(c))}/{sum(1 for c in pos if not human(c))}',
    "streams": [{k: (len(v) if isinstance(v, list) else v) for k, v in s.items()} for s in streams],
}
json.dump({"summary": summary, "rules": RULES, "cases": cases, "streams": streams}, open(OUT, "w"), ensure_ascii=False, indent=1)
print(json.dumps(summary, indent=1))
