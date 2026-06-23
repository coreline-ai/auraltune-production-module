#!/usr/bin/env python3
"""
gen_autoeq_seed.py — Build a prebuilt Room DB seed (autoeq_seed.db) holding the FULL AutoEq
catalog PLUS every parsed profile (preamp + filters), so the app applies any profile instantly
offline (Room createFromAsset). Delta updates later fetch only changed files (GitHub compare API).

Replicates the app's parsers EXACTLY so ids/filters match what the live app would compute:
  - id            = sha256("source|relativePath|name")[:24]   (CatalogIdGenerator)
  - INDEX parsing = IndexMdParser (name/path/source, dedup by name w/ source priority)
  - profile URL   = AutoEqRepository.buildProfileUrl (per-segment %20 / keep () / %2B)
  - filters       = ParametricEqParser (PK/PEQ→PEAKING, LS/LSC→LOW_SHELF, HS/HSC→HIGH_SHELF;
                    Fc/Gain/Q; finite + freq>0 + q>0 + |gain|<=30; <=10 filters; preamp clamp +-30)

Schema + identityHash are read from the exported Room schema (schemas/.../2.json) so the DB is
createFromAsset-compatible without hardcoding SQL. DB stays at schema v2 (commit stored as a
sync_state row, no schema bump).

Usage:
  python3 gen_autoeq_seed.py --commit <sha> [--limit N] [--workers 24]
"""
import argparse, concurrent.futures as cf, hashlib, json, os, re, sqlite3, sys, time, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INDEX_MD = os.path.join(ROOT, "autoeq-data", "src", "main", "assets", "autoeq", "INDEX.md")
SCHEMA_JSON = os.path.join(ROOT, "autoeq-data", "schemas", "com.coreline.autoeq.db.AutoEqDatabase", "2.json")
OUT_DB = os.path.join(ROOT, "autoeq-data", "src", "main", "assets", "databases", "autoeq_seed.db")
PROFILE_BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
SEED_VERSION = 2
SOURCE_PRIORITY = ["oratory1990", "crinacle", "Rtings", "Innerfidelity", "Super Review", "Headphone.com Legacy"]


def cid(source, rel, name):
    return hashlib.sha256(f"{source}|{rel}|{name}".encode("utf-8")).hexdigest()[:24]


def parse_index(text):
    """Replicates IndexMdParser.parse → list of dicts (id,name,measuredBy,relativePath)."""
    best = {}  # name.lower() -> (entry, priority)
    for raw in text.split("\n"):
        t = raw.strip()
        if not t.startswith("- ["):
            continue
        lb = t.find("](")
        if lb < 0:
            continue
        name = t[3:lb]
        if not name:
            continue
        ue = t.rfind(") by ")
        if ue < 0 or ue <= lb + 2:
            continue
        rawpath = t[lb + 2:ue]
        if rawpath.startswith("./"):
            rawpath = rawpath[2:]
        try:
            rel = urllib.parse.unquote(rawpath, errors="strict")
        except Exception:
            rel = rawpath
        sar = t[ue + len(") by "):]
        oi = sar.find(" on ")
        measured = (sar[:oi] if oi >= 0 else sar).strip()
        if not measured:
            continue
        entry = {"id": cid(measured, rel, name), "name": name, "measuredBy": measured, "relativePath": rel}
        key = name.lower()
        pri = SOURCE_PRIORITY.index(measured) if measured in SOURCE_PRIORITY else len(SOURCE_PRIORITY)
        ex = best.get(key)
        if ex is None or pri < ex[1]:
            best[key] = (entry, pri)
    entries = [e for e, _ in best.values()]
    entries.sort(key=lambda e: e["name"].lower())
    return entries


def enc_seg(seg):
    """buildProfileUrl per-segment: space→%20, +→%2B, keep () , non-ascii utf8."""
    return urllib.parse.quote(seg, safe="()")


def profile_url(rel, name):
    last = rel.rsplit("/", 1)[-1] if "/" in rel else name
    filename = f"{last} ParametricEQ.txt"
    enc_path = "/".join(enc_seg(s) for s in rel.split("/"))
    return PROFILE_BASE + enc_path + "/" + enc_seg(filename)


_TYPE = {"PK": "PEAKING", "PEQ": "PEAKING", "LS": "LOW_SHELF", "LSC": "LOW_SHELF", "HS": "HIGH_SHELF", "HSC": "HIGH_SHELF"}
_LEGACY = {"BELL", "LP", "HP", "BP", "NO", "AP", "MODAL"}


def _val(tokens, kw):
    low = [x.lower() for x in tokens]
    try:
        i = low.index(kw.lower())
    except ValueError:
        return None
    if i + 1 >= len(tokens):
        return None
    raw = tokens[i + 1]
    if raw.lower() in ("nan", "infinity", "-infinity", "+infinity"):
        return None
    try:
        v = float(raw)
    except ValueError:
        return None
    return v if (v == v and abs(v) != float("inf")) else None


def parse_profile(text, name):
    """Replicates ParametricEqParser.parse → (preampDb, [filters]) or None on no-valid/legacy."""
    if len(text.encode("utf-8")) > 64 * 1024:
        return None
    if text and text[0] == "﻿":
        text = text[1:]
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    if not text.strip():
        return None
    preamp = 0.0
    filters = []
    for raw in text.split("\n"):
        line = raw.strip()
        if not line:
            continue
        low = line.lower()
        if low.startswith("preamp:"):
            tail = line[line.find(":") + 1:].strip()
            tok = re.split(r"\s+", tail)
            if tok:
                try:
                    pv = float(tok[0])
                    if pv == pv and abs(pv) != float("inf"):
                        preamp = max(-30.0, min(30.0, pv))
                except ValueError:
                    pass
        elif low.startswith("filter"):
            tokens = [x for x in re.split(r"\s+", line) if x]
            if len(tokens) < 2:
                continue
            if not any(x.upper() == "ON" for x in tokens):
                continue
            ttok = next((x for x in tokens if x.upper() in _TYPE or x.upper() in _LEGACY), None)
            if ttok is None:
                continue
            if ttok.upper() in _LEGACY:
                return None  # Reject whole file (REW legacy) — matches parser
            ftype = _TYPE.get(ttok.upper())
            if ftype is None:
                continue
            fc = _val(tokens, "Fc"); g = _val(tokens, "Gain"); q = _val(tokens, "Q")
            if fc is None or g is None or q is None:
                continue
            if not (fc > 0) or not (q > 0) or abs(g) > 30:
                continue
            filters.append((ftype, float(fc), float(g), float(q)))
            if len(filters) >= 10:
                break
    if not filters:
        return None
    return (preamp, filters)


def fetch(url, retries=2, timeout=20):
    last = None
    for _ in range(retries + 1):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "auraltune-seedgen"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                if r.status == 200:
                    return r.read().decode("utf-8", errors="replace")
                last = f"HTTP {r.status}"
        except Exception as e:
            last = str(e)
            time.sleep(0.3)
    return None


def build_db(entries, profiles, commit, now_ms):
    schema = json.load(open(SCHEMA_JSON, encoding="utf-8"))
    ihash = schema["database"]["identityHash"]
    ents = schema["database"]["entities"]
    os.makedirs(os.path.dirname(OUT_DB), exist_ok=True)
    if os.path.exists(OUT_DB):
        os.remove(OUT_DB)
    db = sqlite3.connect(OUT_DB)
    c = db.cursor()
    c.execute("CREATE TABLE android_metadata (locale TEXT)")
    c.execute("INSERT INTO android_metadata VALUES ('en_US')")
    c.execute("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
    c.execute("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)", (ihash,))
    for e in ents:
        c.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
        for idx in e.get("indices", []):
            c.execute(idx["createSql"].replace("${TABLE_NAME}", e["tableName"]))
    # catalog_entries
    c.executemany(
        "INSERT INTO catalog_entries(id,name,normalizedName,measuredBy,relativePath,lastSeenAtMs,isDeleted) "
        "VALUES(?,?,?,?,?,?,0)",
        [(e["id"], e["name"], e["name"].lower(), e["measuredBy"], e["relativePath"], now_ms) for e in entries],
    )
    # profiles + profile_filters
    prows, frows = [], []
    for e in entries:
        pr = profiles.get(e["id"])
        if pr is None:
            continue
        preamp, filters, url, sha = pr
        prows.append((e["id"], e["id"], e["name"], e["measuredBy"], "FETCHED", preamp, 48000.0, url, sha, now_ms, now_ms))
        for i, (t, fc, g, q) in enumerate(filters):
            frows.append((e["id"], i, t, fc, g, q))
    c.executemany(
        "INSERT INTO profiles(id,catalogId,name,measuredBy,source,preampDb,optimizedSampleRate,"
        "sourceUrl,sourceSha256,fetchedAtMs,lastAccessMs) VALUES(?,?,?,?,?,?,?,?,?,?,?)", prows)
    c.executemany(
        "INSERT INTO profile_filters(profileId,position,type,frequencyHz,gainDb,q) VALUES(?,?,?,?,?,?)", frows)
    # sync_state: catalog seed marker + autoeq commit (for delta sync)
    c.execute("INSERT INTO sync_state(`key`,etag,contentSha256,seedVersion,lastSyncAtMs,status) "
              "VALUES('catalog',NULL,NULL,?,?,'seed')", (SEED_VERSION, now_ms))
    c.execute("INSERT INTO sync_state(`key`,etag,contentSha256,seedVersion,lastSyncAtMs,status) "
              "VALUES('autoeq_commit',?,NULL,?,?,'seed')", (commit, SEED_VERSION, now_ms))
    db.commit()
    db.close()
    return ihash, len(prows), len(frows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--commit", default="unknown")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--workers", type=int, default=24)
    args = ap.parse_args()

    text = open(INDEX_MD, encoding="utf-8").read()
    entries = parse_index(text)
    print(f"INDEX entries (deduped): {len(entries)}")
    if args.limit:
        entries = entries[:args.limit]
        print(f"limited to {len(entries)}")

    profiles = {}
    fail = 0
    t0 = time.time()

    def work(e):
        url = profile_url(e["relativePath"], e["name"])
        body = fetch(url)
        if body is None:
            return (e["id"], None)
        pr = parse_profile(body, e["name"])
        if pr is None:
            return (e["id"], None)
        preamp, filters = pr
        sha = hashlib.sha256(body.encode("utf-8")).hexdigest()
        return (e["id"], (preamp, filters, url, sha))

    with cf.ThreadPoolExecutor(max_workers=args.workers) as ex:
        for n, (pid, res) in enumerate(ex.map(work, entries), 1):
            if res is None:
                fail += 1
            else:
                profiles[pid] = res
            if n % 500 == 0:
                print(f"  {n}/{len(entries)}  ok={len(profiles)} fail={fail}  {time.time()-t0:.0f}s")

    now_ms = int(time.time() * 1000)
    ihash, np_, nf = build_db(entries, profiles, args.commit, now_ms)
    size = os.path.getsize(OUT_DB)
    print(f"DONE in {time.time()-t0:.0f}s")
    print(f"  catalog={len(entries)} profiles={np_} filters={nf} fetch_fail={fail}")
    print(f"  identityHash={ihash}")
    print(f"  {OUT_DB}  ({size/1024/1024:.2f} MB)")


if __name__ == "__main__":
    main()
