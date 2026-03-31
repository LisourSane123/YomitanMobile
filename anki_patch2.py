import re

with open("app/src/main/java/com/yomitanmobile/data/anki/AnkiCardCreator.kt", "r") as f:
    text = f.read()

# Zmiana nazwy modelu aby wymusić update i uniknąć błędu liczby kolumn "Bad model/field format" w AnkiDroid.
text = text.replace('MODEL_NAME = "Yomitan-Mobile-v2"', 'MODEL_NAME = "Yomitan-Mobile-v3"')

with open("app/src/main/java/com/yomitanmobile/data/anki/AnkiCardCreator.kt", "w") as f:
    f.write(text)
