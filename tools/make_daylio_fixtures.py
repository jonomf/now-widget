#!/usr/bin/env python3
"""Writes the small SYNTHETIC Daylio fixtures the unit tests read. No real data in here.

Run from anywhere: python3 tools/make_daylio_fixtures.py
"""
import base64, io, json, os, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'app', 'src', 'test', 'resources', 'daylio')

MOODS = [
    {"id": 1, "custom_name": "", "mood_group_id": 1, "mood_group_order": 0, "icon_id": 1, "predefined_name_id": 1, "is_active": True},
    {"id": 2, "custom_name": "", "mood_group_id": 2, "mood_group_order": 0, "icon_id": 2, "predefined_name_id": 2, "is_active": True},
    {"id": 3, "custom_name": "", "mood_group_id": 3, "mood_group_order": 0, "icon_id": 3, "predefined_name_id": 3, "is_active": True},
    {"id": 6, "custom_name": "Up and Down", "mood_group_id": 3, "mood_group_order": 1, "icon_id": 9, "predefined_name_id": -1, "is_active": True},
    {"id": 4, "custom_name": "", "mood_group_id": 4, "mood_group_order": 0, "icon_id": 4, "predefined_name_id": 4, "is_active": True},
    {"id": 5, "custom_name": "", "mood_group_id": 5, "mood_group_order": 0, "icon_id": 5, "predefined_name_id": 5, "is_active": True},
]
TAGS = [
    {"id": 1, "name": "work", "icon": 1, "order": 1, "state": 0, "createdAt": 1546300800000},
    {"id": 2, "name": "family", "icon": 2, "order": 2, "state": 0, "createdAt": 1546300800000},
    {"id": 3, "name": "long", "icon": 3, "order": 3, "state": 0, "createdAt": 1546300800000},
    {"id": 4, "name": "Other", "icon": 4, "order": 4, "state": 0, "createdAt": 1546300800000},
    {"id": 5, "name": "Sam", "icon": 5, "order": 5, "state": 0, "createdAt": 1546300800000},
    {"id": 6, "name": "tired", "icon": 6, "order": 6, "state": 0, "createdAt": 1546300800000},
]

def entry(i, y, mo0, d, h, mi, mood, tags, note, extra=None):
    e = {"id": i, "minute": mi, "hour": h, "day": d, "month": mo0, "year": y,
         "datetime": 0, "timeZoneOffset": -28800000, "mood": mood, "note": note, "tags": tags}
    if extra:
        e.update(extra)
    return e

def android():
    entries = [
        entry(5, 2020, 7, 1, 23, 5, 2, [1, 6], 'Late one, "quoted", with, commas'),
        entry(4, 2020, 0, 4, 9, 30, 1, [2, 5], "Line one\nLine two — café \U0001F600"),
        entry(3, 2019, 11, 31, 3, 15, 6, [3], "Up and down past midnight"),
        entry(2, 2019, 0, 4, 12, 0, 3, [], ""),
        entry(1, 2019, 0, 4, 8, 0, 5, [4, 99], "Tag 99 doesn't exist"),
    ]
    del entries[3]["note"]  # tolerate a missing note
    return {"version": 10, "isReminderOn": False, "customMoods": MOODS, "tags": TAGS,
            "dayEntries": entries, "achievements": [], "goals": [], "prefs": [], "tag_groups": [],
            "metadata": {"number_of_entries": len(entries)}}

def ios():
    entries = [
        entry(0, 2021, 0, 8, 21, 43, 2, [1], "Newest", {"note_title": "A title", "assets": []}),
        entry(0, 2020, 7, 1, 23, 5, 2, [1, 6], 'Late one, "quoted", with, commas', {"note_title": "", "assets": []}),
        entry(0, 2020, 0, 4, 9, 30, 1, [2, 5], "Line one\nLine two — café \U0001F600", {"note_title": "", "assets": []}),
    ]
    for e in entries:
        del e["id"]
        e["timeZoneOffset"] = -1
    return {"version": 15, "platform": "ios", "customMoods": [{k: v for k, v in m.items()} for m in MOODS],
            "tags": TAGS, "dayEntries": entries, "assets": [], "goals": [], "tag_groups": [], "metadata": {}}

def b64(obj):
    raw = base64.b64encode(json.dumps(obj, ensure_ascii=True).encode()).decode()
    return "\n".join(raw[i:i + 76] for i in range(0, len(raw), 76)) + "\n"

def main():
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, 'android_v10.daylio'), 'w') as f:
        f.write(b64(android()))
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('backup.daylio', b64(ios()))
    with open(os.path.join(OUT, 'ios_v15.daylio'), 'wb') as f:
        f.write(buf.getvalue())
    with open(os.path.join(OUT, 'export_new.csv'), 'w', newline='') as f:
        f.write('﻿full_date,date,weekday,time,mood,activities,note_title,note\n'
                '2021-01-08,January 8,Friday,21:43,good,work,A title,Newest\n'
                '2020-08-01,August 1,Saturday,23:05,good,work | tired,,"Late one, ""quoted"", with, commas"\n'
                '2020-01-04,January 4,Saturday,9:30 am,rad,family | Sam,,"Line one\nLine two"\n'
                '2019-12-31,December 31,Tuesday,03:15,Up and Down,long,,\n')
    with open(os.path.join(OUT, 'export_old.csv'), 'w', newline='') as f:
        f.write('year,date,weekday,time,mood,activities,note\r\n'
                '2020,August 1,Saturday,11:05 pm,good,work | tired,"Late one, ""quoted"", with, commas"\r\n'
                '2020,4 January,Saturday,9:30 am,rad,family | Sam,"Line one\r\nLine two"\r\n'
                '2019,December 31,Tuesday,12:15 am,Up and Down,long,\r\n')
    print('wrote', sorted(os.listdir(OUT)))

if __name__ == '__main__':
    main()
