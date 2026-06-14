#!/usr/bin/env python3
"""Upload the local Rouge circuit library to MongoDB.

Reads every ``src/main/resources/rouge/circuits/<id>.json`` and upserts it into
the ``rouge.circuits`` collection, keyed by the circuit ``id``. This is a one-way
sync *to* Mongo: it never writes to or deletes the local JSON files, which stay
the source of truth shipped on the classpath.

Idempotent — re-running replaces existing docs in place, so it doubles as a
"push my latest local edits to Mongo" command.

Usage:
    pip install pymongo dnspython          # dnspython is needed for mongodb+srv
    python3 tools/upload_circuits_to_mongo.py
    python3 tools/upload_circuits_to_mongo.py --db rouge --collection circuits

MONGO_URI is read from the environment or from the repo-root .env file.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

from pymongo import MongoClient, ReplaceOne

REPO_ROOT = Path(__file__).resolve().parent.parent
CIRCUITS_DIR = REPO_ROOT / "src" / "main" / "resources" / "rouge" / "circuits"


def load_mongo_uri() -> str:
    uri = os.environ.get("MONGO_URI")
    if uri:
        return uri
    env_path = REPO_ROOT / ".env"
    if env_path.exists():
        for line in env_path.read_text().splitlines():
            line = line.strip()
            if line.startswith("MONGO_URI="):
                return line[len("MONGO_URI="):].strip().strip('"').strip("'")
    sys.exit("MONGO_URI not found in environment or .env")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", default="rouge", help="database name (default: rouge)")
    parser.add_argument("--collection", default="circuits",
                        help="collection name (default: circuits)")
    args = parser.parse_args()

    files = sorted(CIRCUITS_DIR.glob("*.json"))
    if not files:
        sys.exit(f"No circuit JSON found in {CIRCUITS_DIR}")

    ops: list[ReplaceOne] = []
    now = datetime.now(timezone.utc)
    for path in files:
        try:
            doc = json.loads(path.read_text())
        except json.JSONDecodeError as e:
            sys.exit(f"Invalid JSON in {path.name}: {e}")

        cid = doc.get("id") or path.stem
        doc["_id"] = cid
        # Derived convenience fields (mirrors CircuitPrimitive.isBuildable()).
        doc["buildable"] = bool(doc.get("steps"))
        doc["sourceFile"] = path.name
        doc["updatedAt"] = now
        ops.append(ReplaceOne({"_id": cid}, doc, upsert=True))

    client = MongoClient(load_mongo_uri(), serverSelectionTimeoutMS=20000)
    client.admin.command("ping")  # fail fast on bad credentials/network
    coll = client[args.db][args.collection]
    result = coll.bulk_write(ops, ordered=False)

    total = coll.count_documents({})
    print(f"Uploaded {len(ops)} circuits to {args.db}.{args.collection}")
    print(f"  upserted: {result.upserted_count}  modified: {result.modified_count}")
    print(f"  collection now holds {total} documents")
    client.close()


if __name__ == "__main__":
    main()
