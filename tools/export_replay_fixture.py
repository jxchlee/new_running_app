"""Export one run from a copied Running2 DB for the read-only replay audit test.

The output contains private coordinates. Keep it outside the repository.
Usage: python tools/export_replay_fixture.py COPY_OF_running2.db RUN_ID OUTPUT.tsv
"""

import argparse
import sqlite3
from pathlib import Path


def field(value):
    return "null" if value is None else str(value)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path)
    parser.add_argument("run_id", type=int)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    project = Path(__file__).resolve().parent.parent
    output = args.output.resolve()
    if output == project or project in output.parents:
        parser.error("Private GPS output must be outside the project directory")
    if output.exists():
        parser.error("Output exists; choose a new file to preserve earlier audit inputs")

    db_uri = args.database.resolve().as_uri() + "?mode=ro"
    with sqlite3.connect(db_uri, uri=True) as db:
        db.execute("BEGIN")  # One read snapshot for run, fixes, pauses, and points.
        run = db.execute("SELECT id, startedElapsedMs, endedElapsedMs, distanceM, measuredMs, gapCount, calculationVersion FROM runs WHERE id=?", (args.run_id,)).fetchone()
        if run is None:
            parser.error("Run ID not found")
        fixes = db.execute("SELECT id, fixElapsedMs, latitude, longitude, accuracyM, speedMps FROM raw_fixes WHERE runId=? ORDER BY fixElapsedMs,id", (args.run_id,)).fetchall()
        events = db.execute("SELECT elapsedMs, kind FROM pause_events WHERE runId=? ORDER BY elapsedMs,id", (args.run_id,)).fetchall()
        points = db.execute("SELECT fixId, segment, latitude, longitude, elapsedMs, distanceM FROM track_points WHERE runId=? ORDER BY segment,elapsedMs,id", (args.run_id,)).fetchall()

    pauses = []
    opened = None
    for at, kind in events:
        if kind == "pause":
            opened = at
        elif kind == "resume" and opened is not None:
            pauses.append((opened, at))
            opened = None
    if opened is not None:
        pauses.append((opened, None))

    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8", newline="\n") as out:
        for kind, rows in (("R", [run]), ("F", fixes), ("P", pauses), ("T", points)):
            for row in rows:
                out.write("\t".join([kind, *(field(value) for value in row)]) + "\n")
    print(f"Exported run {args.run_id}: {len(fixes)} raw fixes, {len(pauses)} pauses, {len(points)} points")


if __name__ == "__main__":
    main()
