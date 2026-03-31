import urllib.request
import zipfile
import io
import json

url = "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/KANJIDIC_english.zip"
print("Downloading...")
response = urllib.request.urlopen(url)
zip_file = zipfile.ZipFile(io.BytesIO(response.read()))
for name in zip_file.namelist():
    if "kanji_bank" in name:
        data = json.loads(zip_file.read(name))
        print("Format:", zip_file.read(name).decode()[:500])
        break
