# Dictation history archive — format version 1

This document specifies the file Dicta writes from **Settings → History →
Export history to a file**, and reads from **Import history from a file**. It
is written so that a different app — with a different package name and no
access to Dicta's code — can implement import (and export) from this text
alone. The reference implementation is
`app/src/main/java/dev/zacx/dicta/HistoryArchive.kt` (codec) and
`DictationHistoryStore.importArchive` (merge).

The key words MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119.

## 1. Container

- The archive is a **ZIP** file (PKWARE APPNOTE), MIME type `application/zip`,
  conventionally named `dicta-history-YYYYMMDD-HHMM.zip`.
- Entries MAY be STORED or DEFLATED. Dicta writes DEFLATED entries with data
  descriptors (general-purpose flag bit 3), via `java.util.zip.ZipOutputStream`.
- Entry names are UTF-8, `/`-separated, relative. Directory entries (names
  ending in `/`) carry no data and are ignored by readers.
- No encryption, no ZIP64 requirement (Dicta's history is bounded well below
  4 GiB), no comments are used.
- Why ZIP and not tar: a ZIP reader and writer ship in every Android runtime
  (`java.util.zip`); tar would need a third-party library or a hand-written
  codec. ZIP's per-entry CRC-32 is also a second corruption check underneath
  the SHA-256 below.

A reader SHOULD read the archive as a **stream of local entries** (as
`ZipInputStream` does) and MUST NOT depend on entry order. Integrity comes from
the manifest (§3), not from the central directory.

## 2. Entries

An archive contains exactly these entries and no others:

| name | content |
|---|---|
| `manifest.json` | the manifest (§3) |
| `items.json` | every item's metadata (§4) |
| `audio/<file>` | one audio file per item (§5) |

Dicta writes the audio entries first (newest item first), then `items.json`,
then `manifest.json` **last**. Readers MUST accept any order.

## 3. `manifest.json`

A UTF-8 JSON object:

```json
{
  "format": "dicta-history-archive",
  "formatVersion": 1,
  "createdAtMs": 1700000003000,
  "itemCount": 2,
  "files": [
    {"path": "audio/1700000003000-0a1b2c3d.ogg", "sha256": "<64 hex>", "bytes": 4821},
    {"path": "audio/1700000002000-9f8e7d6c",     "sha256": "<64 hex>", "bytes": 1},
    {"path": "items.json",                       "sha256": "<64 hex>", "bytes": 512}
  ]
}
```

| key | type | rule |
|---|---|---|
| `format` | string | MUST be exactly `dicta-history-archive` |
| `formatVersion` | integer | MUST be `1` for this document. A reader MUST reject any version it does not implement — there is no "newer but compatible" |
| `createdAtMs` | integer | export time, Unix epoch milliseconds. Informational |
| `itemCount` | integer | number of entries in `items.json`'s `items`. If present, MUST equal it |
| `files` | array | one object per entry in the archive **other than `manifest.json` itself** |
| `files[].path` | string | the entry name, unique within `files` |
| `files[].sha256` | string | SHA-256 of the entry's **uncompressed** bytes, **64 lowercase hex digits** |
| `files[].bytes` | integer ≥ 0 | the entry's uncompressed length |

Unknown keys in the manifest object and in `files[]` objects MUST be ignored.
The manifest does not list or hash itself.

## 4. `items.json`

A UTF-8 JSON object `{"items": [ ... ]}`. Each element:

| key | type | meaning / rule |
|---|---|---|
| `id` | string | the item's identity, matching `^[A-Za-z0-9_-]{1,128}$`. Unique within the archive. Dicta generates `%013d-%08x` (zero-padded creation ms, `-`, 8 random hex digits), but readers MUST NOT parse it |
| `createdAtMs` | integer | when the dictation was recorded, Unix epoch ms |
| `audioBytes` | integer | length of the audio file. MUST equal that file's `files[].bytes` |
| `audio` | string | the audio entry's name: `audio/<id>` or `audio/<id>.<ext>`, where `<ext>` matches `^[A-Za-z0-9]{1,16}$` |
| `text` | string | the transcript (may be empty). Any Unicode |
| `fromRemote` | boolean | `true` if `text` came from the remote speech-to-text service, `false` if from the on-device recogniser |
| `audioSeconds` | number ≥ 0, finite | recording duration in seconds. May be written without a fractional part (`12` for `12.0`) |

Types are strict: an integer field holding `1.5`, a string holding `null`, a
missing field — each makes the archive invalid. Unknown keys MUST be ignored.

Dicta writes items newest first (descending `createdAtMs`, ties by `id`). Order
carries no meaning.

## 5. Audio

Each `audio/<file>` entry is the recording exactly as Dicta stored it — no
re-encoding. The extension is the only type information: Dicta records `.ogg`
(Ogg/Opus) or `.m4a` (AAC in MP4) depending on the device's encoder; an item
with no extension has unknown type. Every audio entry MUST belong to exactly one
item.

## 6. Reading (import) — required procedure

A conforming importer MUST verify **everything before writing anything** to its
history:

1. Stage every entry to private scratch storage, computing SHA-256 and length
   while copying. Never use an entry name as a filesystem path (stage under
   generated names) — this is what makes `../`-style names harmless.
2. Reject the archive if: it is not a readable ZIP or ends part-way through an
   entry; an entry name occurs twice; `manifest.json` is absent; `format` or
   `formatVersion` is wrong; any `files[]` row is malformed or a path is listed
   twice; any listed file is absent; any entry other than `manifest.json` is not
   listed; any listed file's SHA-256 or length differs; `items.json` is not
   listed or violates §4; two items share an `id`; an item's `audio` is not of
   the form in §4, is not listed, or is claimed by two items; a listed audio
   file belongs to no item; `audioBytes` disagrees with the manifest;
   `itemCount` disagrees with `items`.
3. Only then merge, by `id`, into the existing history:
   - an `id` already present is **skipped** — never overwritten, even if its
     text or audio differ;
   - a new `id` is **imported**: audio moved into history storage, then the
     history index updated **once** for all imported items (the commit point);
   - Dicta additionally **rejects** (per item, not the archive) a new `id` whose
     destination file name is already occupied by an unindexed file with
     different bytes, and adopts one with identical bytes (an earlier import
     interrupted after moving audio in).
4. Report three counts: imported, skipped, rejected. Every item lands in
   exactly one.

A rejected archive leaves history byte-for-byte unchanged. Truncation inside
the ZIP central directory (after every local entry is complete) is not an
error: every byte of content has already been verified against the manifest.

Bounds Dicta applies while reading (a reader MAY choose others): at most 10 000
entries; `manifest.json` and `items.json` at most 64 MiB each; at most 4 GiB of
uncompressed content in total.

## 7. Writing (export)

An exporter MUST produce an archive the procedure in §6 accepts: hash each
entry's exact bytes, list every entry except the manifest, and give
`audioBytes` the audio's real length. Dicta exports only items whose audio is
present, and reads the written document back through the same verifier before
reporting success.

## 8. Retention after import

Dicta does not apply its retention policy during import, so an import never
deletes an existing item. The policy (365 days, 1000 items, 2 GiB, whichever
binds first) runs as usual at the **next new dictation**, so imported items
older than 365 days, or beyond the count/byte bounds, are removed then. An
importer with a different policy is free to apply its own.

## 9. Example

```
$ unzip -l dicta-history-20261119-1200.zip
  Length      Name
---------     ----
     4821     audio/1700000003000-0a1b2c3d.ogg
        1     audio/1700000002000-9f8e7d6c
      512     items.json
      402     manifest.json
```

`items.json`:

```json
{"items":[
  {"id":"1700000003000-0a1b2c3d","createdAtMs":1700000003000,"audioBytes":4821,
   "audio":"audio/1700000003000-0a1b2c3d.ogg","text":"the heron stood very still",
   "fromRemote":true,"audioSeconds":3.217},
  {"id":"1700000002000-9f8e7d6c","createdAtMs":1700000002000,"audioBytes":1,
   "audio":"audio/1700000002000-9f8e7d6c","text":"","fromRemote":false,"audioSeconds":12}
]}
```

Checking an archive by hand (`sha256sum` output must match `files[].sha256`):

```
unzip -d x dicta-history-20261119-1200.zip && (cd x && sha256sum items.json audio/*)
```
