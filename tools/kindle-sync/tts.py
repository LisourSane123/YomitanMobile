"""Pronunciation for Kindle cards.

Reads "output path<TAB>expression<TAB>reading<TAB>pitch" lines from stdin and
writes one mp3 per line; prints the paths it wrote.

VOICEVOX (neural, offline) when its files are installed, Open JTalk otherwise.
Open JTalk's HMM voice is intelligible but robotic, far below the phone's
neural TTS; it stays only as the fallback.

VOICEVOX is not given the headword. It is given the dictionary READING with
the accent written into it (AquesTalk notation, ' after the accented mora),
taken from the same pitch dictionary that draws the card's pitch diagram. A TTS
reading a headword with no context picks a plausible reading and a plausible
accent (行った, 一日, 開く); this way the recording says what the card prints.
Without a known accent the reading alone goes in and VOICEVOX's own analysis
decides.

The voice is picked per word from a few neutral narrator voices, keyed by the
word so a rerun gives the same file — the phone's random-voice setting, made
deterministic.
"""
import hashlib
import os
import subprocess
import sys
import wave

VOICEVOX_DIR = os.environ.get(
    "KINDLE_SYNC_VOICEVOX", os.path.expanduser("~/.local/share/kindle-sync/voicevox/core")
)
# Style ids: VOICEVOX Nemo 女声1-3 / 男声1-2 (generic narrator voices) and
# No.7 アナウンス. The character voices are anime-styled and read a single word
# with a lot of attitude.
VOICES = {"n0.vvm": [10005, 10007, 10004, 10001, 10000], "6.vvm": [30]}

SMALL = set("ャュョァィゥェォヮ")


def to_katakana(text: str) -> str:
    return "".join(chr(ord(c) + 0x60) if "ぁ" <= c <= "ゖ" else c for c in text)


def to_hiragana(text: str) -> str:
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in text)


def morae(kana: str) -> list[str]:
    out: list[str] = []
    for c in kana:
        if c in SMALL and out:
            out[-1] += c
        else:
            out.append(c)
    return out


def accented_kana(reading: str, pitch: str) -> str | None:
    """ツキツケ'ル for つきつける [4]; heiban (0) marks the last mora, which is
    how VOICEVOX spells "no fall inside the word"."""
    kana = to_katakana(reading)
    if not kana or not all("ァ" <= c <= "ヶ" or c == "ー" for c in kana):
        return None
    first = pitch.split(",")[0].strip()
    if not first.isdigit():
        return None
    m = morae(kana)
    position = int(first) or len(m)
    if position > len(m):
        return None
    return "".join(m[:position]) + "'" + "".join(m[position:])


class Voicevox:
    def __init__(self) -> None:
        from voicevox_core.blocking import Onnxruntime, OpenJtalk, Synthesizer, VoiceModelFile

        ort = Onnxruntime.load_once(
            filename=os.path.join(VOICEVOX_DIR, "onnxruntime/lib/libvoicevox_onnxruntime.so.1.17.3")
        )
        self.synthesizer = Synthesizer(ort, OpenJtalk(os.path.join(VOICEVOX_DIR, "dict/open_jtalk_dic_utf_8-1.11")))
        self.styles: list[int] = []
        for name, styles in VOICES.items():
            with VoiceModelFile.open(os.path.join(VOICEVOX_DIR, "models/vvms", name)) as model:
                self.synthesizer.load_voice_model(model)
            self.styles += styles

    def wav(self, expression: str, reading: str, pitch: str) -> bytes:
        style = self.styles[int(hashlib.sha1(f"{expression}|{reading}".encode()).hexdigest(), 16) % len(self.styles)]
        kana = accented_kana(reading, pitch)
        if kana:
            query = self.synthesizer.create_audio_query_from_kana(kana, style)
        else:
            query = self.synthesizer.create_audio_query(reading or expression, style)
        # A single word needs no lead-in or tail of silence.
        query.pre_phoneme_length = 0.05
        query.post_phoneme_length = 0.1
        return self.synthesizer.synthesis(query, style)


class OpenJtalkFallback:
    def __init__(self) -> None:
        import pyopenjtalk  # noqa: F401

    def wav(self, expression: str, reading: str, pitch: str) -> bytes:
        import io

        import numpy as np
        import pyopenjtalk

        text = expression
        if reading and to_hiragana(pyopenjtalk.g2p(expression, kana=True)) != to_hiragana(reading):
            text = reading
        samples, rate = pyopenjtalk.tts(text)
        buffer = io.BytesIO()
        with wave.open(buffer, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(rate)
            w.writeframes(np.clip(samples, -32768, 32767).astype(np.int16).tobytes())
        return buffer.getvalue()


def main() -> None:
    try:
        engine = Voicevox()
    except Exception as e:
        print(f"VOICEVOX unavailable ({e}), falling back to Open JTalk", file=sys.stderr)
        engine = OpenJtalkFallback()
    for line in sys.stdin:
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 3:
            continue
        out, expression, reading = parts[0], parts[1], parts[2]
        pitch = parts[3] if len(parts) > 3 else ""
        try:
            data = engine.wav(expression, reading, pitch)
        except Exception as e:  # one bad word must not cost the rest
            print(f"tts failed for {expression}: {e}", file=sys.stderr)
            continue
        wav = out + ".wav"
        with open(wav, "wb") as f:
            f.write(data)
        # Loudness-normalised the way a speech track is (EBU R128, -16 LUFS),
        # so a Kindle card is neither quieter nor louder than the next one.
        subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-i", wav, "-af", "loudnorm=I=-16:TP=-1.5:LRA=11",
             "-ar", "44100", "-codec:a", "libmp3lame", "-q:a", "3", out],
            check=True,
        )
        os.remove(wav)
        print(out, flush=True)


if __name__ == "__main__":
    main()
