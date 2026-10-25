"""Creates the spoken test phrases (16 kHz WAV) for the emulator voice test."""
import os, subprocess
from gtts import gTTS
os.makedirs("voice", exist_ok=True)
PHRASES = {
    "hey_diya": ("Hey Diya", "en", "co.in"),
    "hey_diya_us": ("Hey Diya", "en", "com"),
    "what_is_25_times_4": ("What is 25 times 4?", "en", "co.in"),
    "whats_the_time": ("What's the time?", "en", "co.in"),
    "hey_diya_time_one_breath": ("Hey Diya, what's the time?", "en", "co.in"),
    "tell_me_a_joke": ("Tell me a joke", "en", "co.in"),
    "hey_there": ("Hey there, how are you doing today?", "en", "co.in"),
    "kal_ka_mausam": ("kal ka mausam batao", "hi", "co.in"),
    "thank_you": ("Thank you", "en", "co.in"),
}
for name, (text, lang, tld) in PHRASES.items():
    gTTS(text=text, lang=lang, tld=tld).save(f"voice/{name}.mp3")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", f"voice/{name}.mp3", "-ar", "48000", "-ac", "2", f"voice/{name}.wav"], check=True)
    print("made", name)
