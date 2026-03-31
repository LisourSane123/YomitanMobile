with open("app/src/main/java/com/yomitanmobile/data/anki/AnkiCardCreator.kt", "r") as f:
    lines = f.readlines()

for i, line in enumerate(lines):
    if "kanji.meanings.isNotEmpty" in line:
        lines[i] = '                (if (kanji.meanings.isNotEmpty()) "<br>Znaczenie: " + kanji.meanings else "") +\n'

with open("app/src/main/java/com/yomitanmobile/data/anki/AnkiCardCreator.kt", "w") as f:
    f.writelines(lines)
