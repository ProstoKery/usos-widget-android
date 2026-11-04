"""Writes a demo USOS-style timetable around the current time.

usage: make_ics.py <IANA time zone> <output.ics> [--changed]
--changed moves one class, cancels one and changes a room (to trigger the change notification).
"""
import sys
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

tz_name, out = sys.argv[1], sys.argv[2]
changed = "--changed" in sys.argv
tz = ZoneInfo(tz_name)
now = datetime.now(tz).replace(second=0, microsecond=0)
now = now - timedelta(minutes=now.minute % 5)
day = timedelta(days=1)


def at(base, h, m):
    return base.replace(hour=h, minute=m)


# (uid, summary, start, minutes, room, group)
running = now - timedelta(minutes=30)
classes = [
    ("sm-1", "W - Sztuczna Inteligencja\\; SPEC: Inżynieria Sztucznej Inteligencji", running, 115, "114", 1),
    ("sm-2", "Lab - Uczenie maszynowe\\; SPEC: Inżynieria Sztucznej Inteligencji", running + timedelta(minutes=130), 115,
     "A5" if changed else "127 (Comp. Room)", 1),
    ("sm-3", "W - Logika rozmyta\\; SPEC: Inżynieria Sztucznej Inteligencji", at(now + day, 8, 0), 115, "114", 1),
    ("sm-4", "Lab - Systemy operacyjne", at(now + day, 11 if changed else 10, 10), 115, "131 (Comp. Room)", 1),
    ("sm-5", "C - Rozproszone systemy komputerowe", at(now + 2 * day, 12, 30), 115, "125 (Comp. Room)", 4),
    ("sm-6", "W - PDW IV: Projekt zespołowy", at(now + 3 * day, 16, 50), 115, "A3", 1),
]
if changed:
    classes = [c for c in classes if c[0] != "sm-5"]


def fold(line):
    """iCalendar lines are folded at 75 characters, like USOS does."""
    parts = [line[:75]] + [line[i:i + 74] for i in range(75, len(line), 74)]
    return "\r\n ".join(parts)


fmt = "%Y%m%dT%H%M%S"
lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//USOS Widget e2e//EN", f"X-WR-TIMEZONE:{tz_name}"]
for uid, summary, start, minutes, room, group in classes:
    end = start + timedelta(minutes=minutes)
    lines += [
        "BEGIN:VEVENT",
        fold(f"SUMMARY:{summary}"),
        f"DTSTART;VALUE=DATE-TIME:{start.strftime(fmt)}",
        f"DTEND;VALUE=DATE-TIME:{end.strftime(fmt)}",
        f"UID:{uid}@usosapps.vistula.edu.pl",
        fold(f"DESCRIPTION:Sala: {room}\\nVistula University\\n\\nhttp://usosweb.vistula.edu.pl/kontroler.php"
             f"?_action=katalog2/przedmioty/pokazZajecia&gr_nr={group}&zaj_cyk_id=1\\n"),
        "LOCATION:Stokłosy 3\\, 02-787 Warszawa",
        "STATUS:CONFIRMED",
        "END:VEVENT",
    ]
lines.append("END:VCALENDAR")
with open(out, "w", encoding="utf-8", newline="") as f:
    f.write("\r\n".join(lines) + "\r\n")
print(f"{out}: {len(classes)} classes, now = {now:%Y-%m-%d %H:%M} {tz_name}{' (changed)' if changed else ''}")
