#!/usr/bin/env python3
"""#190: offline, private music catalog import; dry-run unless --apply.
Requires Python 3.10+. For --apply only: pip install 'psycopg[binary]'.
Set MUSIC_CATALOG_DSN via environment; never pass passwords on command line.
"""
import argparse
import csv
import os
import uuid
import re
from pathlib import Path

TABLES = [
    ("works.csv", "music_catalog_works", [
        ("source_work_id","work_id"),("title","title"),("artist","artist"),
        ("normalized_title","normalized_title"),("normalized_artist","normalized_artist"),
        ("identity_status","identity_status"),("grouping_method","grouping_method")],
     ["source_work_id"]),
    ("arrangements.csv","music_catalog_arrangements", [
        ("source_arrangement_id","arrangement_id"),("source_work_id","work_id"),
        ("name","name"),("identity_status","identity_status"),("tempo_raw","tempo"),
        ("time_signatures_raw","time_signatures"),("track_count_raw","track_count"),
        ("instruments_raw","instruments"),("string_counts_raw","string_counts"),
        ("measure_count_raw","measure_count"),("note_count_raw","note_count"),
        ("parser_status","parser_status"),("parser_source","parser_source")],
     ["source_arrangement_id"]),
    ("files.csv","music_catalog_source_files", [
        ("source_file_id","file_id"),("source_arrangement_id","arrangement_id"),
        ("relative_path","relative_path"),("original_filename","original_filename"),
        ("source_group","source_group"),("extension","extension"),
        ("size_bytes","size_bytes"),("rights_status","rights_status"),
        ("visibility","visibility"),("original_title","original_title"),
        ("original_artist","original_artist"),("raw_title","raw_title"),
        ("raw_artist","raw_artist"),("encoding_used","encoding_used"),
        ("text_quality_flags","text_quality_flags"),("parse_error","parse_error")],
     ["source_file_id"]),
    ("review-queue.csv","music_catalog_review_queue", [
        ("source_file_id","file_id"),("review_reasons","review_reasons"),
        ("title","title"),("artist","artist"),("parser_status","parser_status")],
     ["source_file_id"])
]

def read_rows(folder, filename, mapping):
    result=[]
    with (folder / filename).open(encoding="utf-8-sig",newline="") as file:
        reader=csv.DictReader(file)
        absent={source for _,source in mapping}-set(reader.fieldnames or [])
        if absent:
            raise ValueError(f"{filename}: missing CSV columns {sorted(absent)}")
        for line,row in enumerate(reader,2):
            if None in row:
                raise ValueError(f"{filename}:{line}: malformed CSV")
            result.append(row)
    return result

def verify(data):
    works, arrangements, files, reviews=data
    for block,name,id_field in [(works,"works","work_id"),(arrangements,"arrangements","arrangement_id"),
                                (files,"files","file_id"),(reviews,"reviews","file_id")]:
        ids=[row[id_field] for row in block]
        if len(ids)!=len(set(ids)) or not all(ids):
            raise ValueError(f"{name}: empty or duplicate identifiers")
    w={row["work_id"] for row in works}
    a={row["arrangement_id"] for row in arrangements}
    f={row["file_id"] for row in files}
    if any(row["work_id"] not in w for row in arrangements):
        raise ValueError("Orphan arrangement")
    if any(row["arrangement_id"] not in a for row in files):
        raise ValueError("Orphan source file")
    if any(row["file_id"] not in f for row in reviews):
        raise ValueError("Orphan review")
    paths=[row["relative_path"].casefold() for row in files]
    if len(paths)!=len(set(paths)):
        raise ValueError("Duplicate source paths")
    for row in works:
        if row["identity_status"] not in {"CANDIDATE","UNRESOLVED","VERIFIED"}:
            raise ValueError("Invalid work identity status")
    for row in arrangements:
        if not row["name"] or not row["parser_status"]:
            raise ValueError("Incomplete arrangement")
    for row in reviews:
        if not row["review_reasons"] or not row["parser_status"]:
            raise ValueError("Incomplete review")
    for row in files:
        if row["visibility"]!="PRIVATE":
            raise ValueError("Refusing non-private source file")
        relative = row["relative_path"].replace("\\\\", "/")
        if (not relative or relative.startswith("/") or
                re.match(r"^[A-Za-z]:", relative) or
                any(part in {"", ".", ".."} for part in relative.split("/"))):
            raise ValueError("Invalid source-relative path")
        if not row["file_id"] or not row["arrangement_id"] or not row["original_filename"] or not row["source_group"] or not row["extension"] or not row["rights_status"]:
            raise ValueError("Incomplete file metadata")
        n=int(row["size_bytes"])
        if n<0:
            raise ValueError("Negative source size")
    print("Validated", *(f"{name}={len(rows)}" for (name,*_),rows in zip(TABLES,data)))
    print("All records remain private metadata; originals are never uploaded.")

# PostgreSQL text cannot contain U+0000. Never modify source CSVs.
# Identifiers and paths are identity-bearing: reject, don't silently rewrite them.
IDENTITY_COLUMNS = {"source_work_id", "source_arrangement_id", "source_file_id",
                    "relative_path"}

def postgres_text(value, column, stats):
    if not isinstance(value, str) or "\\x00" not in value:
        return value
    if column in IDENTITY_COLUMNS:
        raise ValueError(f"NUL found in identity/path field {column}; review source data")
    stats[column] = stats.get(column, 0) + value.count("\\x00")
    return value.replace("\\x00", "")

def apply(data, account_id, dsn):
    try:
        import psycopg
    except ImportError as e:
        raise SystemExit("Install psycopg for --apply: py -3 -m pip install 'psycopg[binary]'") from e
    scrubbed = {}
    with psycopg.connect(dsn) as conn:
        with conn.transaction():
            with conn.cursor() as cur:
                cur.execute("SELECT 1 FROM accounts WHERE id=%s AND status='ACTIVE'",(account_id,))
                if cur.fetchone() is None:
                    raise ValueError("Account missing or inactive; transaction aborted")
                for i,(filename,table,mapping,keys) in enumerate(TABLES):
                    staging=f"stage_music_{i}"
                    cur.execute(f"CREATE TEMP TABLE {staging} (LIKE {table}) ON COMMIT DROP")
                    columns=["owner_account_id"]+[col for col,_ in mapping]
                    with cur.copy(f"COPY {staging} ({', '.join(columns)}) FROM STDIN") as copy:
                        for row in data[i]:
                            values=[account_id]+[
                                postgres_text(row[source], col, scrubbed)
                                for col,source in mapping
                            ]
                            if table=="music_catalog_source_files":
                                values[columns.index("size_bytes")]=int(row["size_bytes"])
                            copy.write_row(tuple(values))
                    updates=", ".join(f"{col}=EXCLUDED.{col}" for col in columns if col not in (["owner_account_id"]+keys))
                    cur.execute(
                        f"INSERT INTO {table} ({', '.join(columns)}) "
                        f"SELECT {', '.join(columns)} FROM {staging} WHERE true "
                        f"ON CONFLICT (owner_account_id, {', '.join(keys)}) DO UPDATE SET {updates}"
                    )
                    print(f"Upserted {len(data[i])} rows into {table}")
    print("Committed. No music file bytes were imported.")
    print("Removed NUL bytes from PostgreSQL text values:", sum(scrubbed.values()),
          "(by field:", sorted(scrubbed.items()), ")")

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--directory",required=True,type=Path)
    p.add_argument("--apply",action="store_true",help="Write into DB (default is validation only)")
    p.add_argument("--account-id",help="Existing active account UUID, mandatory on apply")
    args=p.parse_args()
    folder=args.directory.expanduser().resolve()
    data=[read_rows(folder,name,mapping) for name,_,mapping,_ in TABLES]
    verify(data)
    if not args.apply:
        print("DRY-RUN ONLY. No database changes.")
        return
    if not args.account_id:
        p.error("--account-id required for --apply")
    account=uuid.UUID(args.account_id)
    dsn=os.environ.get("MUSIC_CATALOG_DSN")
    if not dsn:
        p.error("MUSIC_CATALOG_DSN environment variable is required for --apply")
    apply(data,account,dsn)

if __name__=="__main__":
    main()
