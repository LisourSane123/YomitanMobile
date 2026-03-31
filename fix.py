import re

with open("app/src/main/java/com/yomitanmobile/ui/cardstyle/CardStyleScreen.kt", "r") as f:
    text = f.read()

text = text.replace("randomFonts - font", "randomFonts.minus(font)")
text = text.replace("randomFonts + font", "randomFonts.plus(font)")
text = text.replace("randomVoices - voice", "randomVoices.minus(voice)")
text = text.replace("randomVoices + voice", "randomVoices.plus(voice)")

with open("app/src/main/java/com/yomitanmobile/ui/cardstyle/CardStyleScreen.kt", "w") as f:
    f.write(text)
