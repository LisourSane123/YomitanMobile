import re

with open("app/src/main/java/com/yomitanmobile/data/anki/AnkiCardCreator.kt", "r") as f:
    text = f.read()

# Replace FIELD_NAMES
old_fields = 'val FIELD_NAMES = arrayOf("Front", "Reading", "Meaning", "PitchAccent", "Frequency", "Audio", "Sentence")'
new_fields = 'val FIELD_NAMES = arrayOf("Front", "Reading", "Meaning", "PitchAccent", "Frequency", "Audio", "Sentence", "KanjiBreakdown")'
text = text.replace(old_fields, new_fields)

# Replace CARD_BACK_TEMPLATE
old_back = '{{#Sentence}}<div class="sentence">{{Sentence}}</div>{{/Sentence}}\n            </div>'
new_back = '{{#Sentence}}<div class="sentence">{{Sentence}}</div>{{/Sentence}}\n                {{#KanjiBreakdown}}<div class="kanji-breakdown">{{KanjiBreakdown}}</div>{{/KanjiBreakdown}}\n            </div>'
text = text.replace(old_back, new_back)

# Add CSS for Kanji Breakdown
old_css_hr = '            hr { border: none; border-top: 1px solid #444; margin: 15px 0; }'
new_css_hr = '            hr { border: none; border-top: 1px solid #444; margin: 15px 0; }\n            .kanji-breakdown { font-size: 16px; color: #ccc; margin-top: 15px; padding: 12px; background: #252525; border-radius: 8px; text-align: left; } .kanji-item { margin-bottom: 8px; } .kanji-char { font-size: 24px; color: #fff; margin-right: 8px; font-weight: bold; }'
text = text.replace(old_css_hr, new_css_hr)

with open("app/src/main/java/com/yomitanmobile/data/anki/AnkiCardCreator.kt", "w") as f:
    f.write(text)
