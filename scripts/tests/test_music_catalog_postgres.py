import importlib.util
import os
import pathlib
import uuid

import psycopg

BASE = pathlib.Path(__file__).resolve().parents[2]
SRC = BASE / "music_catalog_import.py"
MIGRATION = BASE.parent / "backend/src/main/resources/db/migration/V27__private_music_catalog_index.sql"
spec = importlib.util.spec_from_file_location("music_import", SRC)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

DSN = os.environ["MUSIC_CATALOG_DSN"]
ACCOUNT = uuid.uuid4()


def fixture():
    data = []
    for filename, table, mapping, _ in mod.TABLES:
        row = {source: "" for _, source in mapping}
        data.append([row])
    work, arrangement, file, review = (block[0] for block in data)
    work.update(work_id="w1",title="Test",artist="",normalized_title="test",
                normalized_artist="",identity_status="CANDIDATE",
                grouping_method="synthetic")
    arrangement.update(arrangement_id="a1",work_id="w1",name="Test guitar",
                       identity_status="UNVERIFIED",parser_status="PARSED",
                       parser_source="ci_fixture")
    file.update(file_id="f1",arrangement_id="a1",relative_path="Gitara\\test.gp5",
                original_filename="test.gp5",source_group="Gitara",
                extension=".gp5",size_bytes="123",rights_status="rights_unverified",
                visibility="PRIVATE")
    review.update(file_id="f1",review_reasons="ci_fixture",parser_status="PARSED")
    return data


with psycopg.connect(DSN) as conn:
    with conn.cursor() as cur:
        cur.execute("""CREATE TABLE accounts (
          id UUID PRIMARY KEY, status VARCHAR(16) NOT NULL)""")
        cur.execute("INSERT INTO accounts(id,status) VALUES (%s,'ACTIVE')",(ACCOUNT,))
        cur.execute(MIGRATION.read_text(encoding="utf-8"))
print("V27 DDL executed on disposable PostgreSQL")

data = fixture()
mod.verify(data)
mod.apply(data,ACCOUNT,DSN)
mod.apply(data,ACCOUNT,DSN)

with psycopg.connect(DSN) as conn:
    with conn.cursor() as cur:
        for _,table,_,_ in mod.TABLES:
            cur.execute(f"SELECT count(*) FROM {table}")
            assert cur.fetchone()[0] == 1, table

# A second account may use the same source IDs without exposing account 1's rows.
other = uuid.uuid4()
with psycopg.connect(DSN) as conn:
    with conn.cursor() as cur:
        cur.execute("INSERT INTO accounts(id,status) VALUES (%s,'ACTIVE')",(other,))
mod.apply(fixture(), other, DSN)
with psycopg.connect(DSN) as conn:
    with conn.cursor() as cur:
        for _,table,_,_ in mod.TABLES:
            cur.execute(f"SELECT count(*) FROM {table}")
            assert cur.fetchone()[0] == 2, table

# Force a database-level constraint failure after earlier tables are staged/upserted.
bad = fixture()
bad[2][0]["visibility"] = "PUBLIC"
bad[0][0]["title"] = "SHOULD_ROLL_BACK"
try:
    mod.apply(bad, ACCOUNT, DSN)
    raise AssertionError("Database must reject public source file")
except psycopg.Error:
    pass
with psycopg.connect(DSN) as conn:
    with conn.cursor() as cur:
        cur.execute("SELECT title FROM music_catalog_works WHERE owner_account_id=%s",(ACCOUNT,))
        assert cur.fetchone()[0] == "Test", "Import did not roll back"

print("PostgreSQL transaction, repeat import, account isolation, and rollback: OK")
