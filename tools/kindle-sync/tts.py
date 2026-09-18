"""Pronunciation for Kindle cards, with Open JTalk (pyopenjtalk).

Reads "output path<TAB>expression<TAB>reading" lines from stdin and writes one
mp3 per line; prints the paths it wrote. Open JTalk is given the WRITTEN form
when its own reading of it agrees with the dictionary's, because that is where
its accent dictionary helps; otherwise it is given the reading, since a TTS
reading a headword with no context picks a plausible reading, not the right one
(行った, 一日, 開く).
"""
import subprocess
import sys
import wave

import numpy as np
import pyopenjtalk


def to_hiragana(text: str) -> str:
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in text)


def main() -> None:
    for line in sys.stdin:
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 3:
            continue
        out, expression, reading = parts[0], parts[1], parts[2]
        text = expression
        if reading and to_hiragana(pyopenjtalk.g2p(expression, kana=True)) != to_hiragana(reading):
            text = reading
        try:
            samples, rate = pyopenjtalk.tts(text)
        except Exception as e:  # one bad word must not cost the rest
            print(f"tts failed for {expression}: {e}", file=sys.stderr)
            continue
        wav = out + ".wav"
        with wave.open(wav, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(rate)
            w.writeframes(np.clip(samples, -32768, 32767).astype(np.int16).tobytes())
        subprocess.run(["lame", "--quiet", "-V4", wav, out], check=True)
        subprocess.run(["rm", "-f", wav])
        print(out, flush=True)


if __name__ == "__main__":
    main()
