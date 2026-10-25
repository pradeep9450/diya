"""
Wake-word lab test (runs on GitHub Actions, which has internet).
Synthesises "Hey Diya" and other phrases in several voices, feeds them to the
same Vosk model Diya uses, and records exactly what Vosk hears:
  - free decoding (any word)
  - grammar decoding (only the wake phrases + [unk])
Writes results.json for evaluation against Diya's matcher.
"""
import json, os, subprocess, sys, wave
from vosk import Model, KaldiRecognizer, SetLogLevel

SetLogLevel(-1)
MODEL = sys.argv[1]
OUT = sys.argv[2]
GRAMMAR = json.load(open(sys.argv[3]))
os.makedirs("audio", exist_ok=True)

POSITIVE = ["Hey Diya", "Hey Diya.", "Hi Diya", "Hey Diya, what's the time?", "Hey Diya, set a timer", "Hey, Diya"]
NEGATIVE = [
    "Hey there, how are you?", "What is the idea?", "Hello dear friend", "I went to India yesterday",
    "Please pass the water", "Hey, did you eat?", "They are here now", "Good morning everyone",
    "Give me a good idea", "Hey Siri", "Hey Google", "Alexa, play music", "Light the diya for Diwali",
    "The dog is in the garden", "Can you hear me?", "Hey Priya", "Hey Riya", "Hey Dinesh",
]
HINDI_POS = ["हे दिया", "हाय दिया"]
HINDI_NEG = ["आज मौसम कैसा है", "क्या हाल है", "दीया जलाओ", "हे भगवान"]

def gtts(text, lang, tld, path):
    from gtts import gTTS
    gTTS(text=text, lang=lang, tld=tld).save(path + ".mp3")
    return path + ".mp3"

def espeak(text, voice, path):
    subprocess.run(["espeak-ng", "-v", voice, "-w", path + ".wav", text], check=True, capture_output=True)
    return path + ".wav"

def to16k(src, dst, tempo=1.0, noise=False):
    filt = f"atempo={tempo}" if tempo != 1.0 else "anull"
    if noise:
        cmd = ["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-f", "lavfi", "-i",
               "anoisesrc=color=pink:amplitude=0.03:sample_rate=16000", "-filter_complex",
               f"[0:a]aresample=16000,{filt}[v];[v][1:a]amix=inputs=2:duration=first:weights=1 1",
               "-ac", "1", "-ar", "16000", dst]
    else:
        cmd = ["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-af", f"aresample=16000,{filt}",
               "-ac", "1", "-ar", "16000", dst]
    # pad 0.6s silence on both ends like real speech
    subprocess.run(cmd, check=True)
    padded = dst.replace(".wav", "_p.wav")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", dst, "-af",
                    "adelay=600|600,apad=pad_dur=0.8", "-ac", "1", "-ar", "16000", padded], check=True)
    return padded

def decode(model, path, grammar=None):
    wf = wave.open(path, "rb")
    rec = KaldiRecognizer(model, 16000, json.dumps(grammar)) if grammar else KaldiRecognizer(model, 16000)
    texts = []
    while True:
        data = wf.readframes(2000)  # 0.125 s, like Diya's audio buffer
        if not data:
            break
        if rec.AcceptWaveform(data):
            t = json.loads(rec.Result()).get("text", "")
            if t: texts.append(("final", t))
        else:
            t = json.loads(rec.PartialResult()).get("partial", "")
            if t and (not texts or texts[-1] != ("partial", t)): texts.append(("partial", t))
    t = json.loads(rec.FinalResult()).get("text", "")
    if t: texts.append(("final", t))
    return texts

model = Model(MODEL)
vocab = {}
for w in ["diya", "dia", "deeya", "diyaa", "dear", "deer", "idea", "hey", "hi", "hay", "the", "ya", "yeah",
          "d", "dee", "di", "de", "they", "a", "priya", "riya", "india", "diwali"]:
    try:
        vocab[w] = model.find_word(w) >= 0
    except Exception as e:
        vocab[w] = f"n/a ({e})"

voices = [("gtts-in", lambda t, p: gtts(t, "en", "co.in", p)), ("gtts-us", lambda t, p: gtts(t, "en", "com", p)),
          ("gtts-uk", lambda t, p: gtts(t, "en", "co.uk", p)), ("espeak-us", lambda t, p: espeak(t, "en-us", p)),
          ("espeak-in", lambda t, p: espeak(t, "en-029", p))]
cases = []
i = 0
def add(text, positive, voice_name, maker, variants):
    global i
    for tempo, noise in variants:
        i += 1
        base = f"audio/{i}"
        try:
            raw = maker(text, base)
            wav = to16k(raw, base + "_16k.wav", tempo, noise)
        except Exception as e:
            cases.append({"text": text, "positive": positive, "voice": voice_name, "error": str(e)})
            continue
        cases.append({
            "text": text, "positive": positive, "voice": voice_name, "tempo": tempo, "noise": noise,
            "free": decode(model, wav), "grammar": decode(model, wav, GRAMMAR),
        })

full = [(1.0, False), (0.85, False), (1.2, False), (1.0, True)]
for v, mk in voices:
    for t in POSITIVE:
        add(t, True, v, mk, full if v.startswith("gtts") else [(1.0, False)])
    for t in NEGATIVE:
        add(t, False, v, mk, [(1.0, False)] if v.startswith("gtts") else [])
for t in HINDI_POS:
    add(t, True, "gtts-hi", lambda t, p: gtts(t, "hi", "co.in", p), full)
for t in HINDI_NEG:
    add(t, False, "gtts-hi", lambda t, p: gtts(t, "hi", "co.in", p), [(1.0, False)])

json.dump({"vocab": vocab, "grammar": GRAMMAR, "cases": cases}, open(OUT, "w"), ensure_ascii=False, indent=1)
print("cases:", len(cases), "vocab:", vocab)
