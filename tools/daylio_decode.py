#!/usr/bin/env python3
"""Decodes a Daylio backup (.daylio) to JSON on stdout.

Handles both formats: the Android one (the file is base64 of one JSON object) and the iOS one
(a zip holding backup.daylio, which is base64 of the JSON). Told apart by the zip's PK signature.

  python3 tools/daylio_decode.py backup.daylio > backup.json
  python3 tools/daylio_decode.py --summary backup.daylio   # counts only, no notes

Backups hold private notes: never commit one, or anything decoded from one.
"""
import base64, io, json, sys, zipfile


def decode(data: bytes) -> dict:
    if data[:2] == b'PK':
        z = zipfile.ZipFile(io.BytesIO(data))
        names = [n for n in z.namelist() if n.rsplit('/', 1)[-1] == 'backup.daylio']
        if not names:
            names = [n for n in z.namelist() if n.endswith('.daylio')]
        if not names:
            raise SystemExit('no backup.daylio in the zip')
        return decode(z.read(names[0]))
    text = data.lstrip(b'\xef\xbb\xbf \r\n\t')
    if text.startswith(b'{'):
        return json.loads(text.decode('utf-8'))
    return json.loads(base64.b64decode(b''.join(text.split())).decode('utf-8'))


def main(argv):
    summary = '--summary' in argv
    paths = [a for a in argv if a != '--summary']
    if len(paths) != 1:
        raise SystemExit(__doc__)
    with open(paths[0], 'rb') as f:
        d = decode(f.read())
    if summary:
        entries = d.get('dayEntries') or []
        print(json.dumps({
            'version': d.get('version'),
            'entries': len(entries),
            'moods': len(d.get('customMoods') or []),
            'tags': len(d.get('tags') or []),
        }))
    else:
        json.dump(d, sys.stdout, ensure_ascii=False, indent=1)
        sys.stdout.write('\n')


if __name__ == '__main__':
    main(sys.argv[1:])
