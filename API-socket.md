# BioImage socket API (sessions + shared-memory deposit)

How a client talks to the Unix-domain-socket microservice
(`BioImageSocketService`).  Its signature capability is transferring **raw
pixel volumes** into a client-owned shared-memory region with no copy through
the socket and no re-encoding (`deposit`), but it is also a **session-capable**
transport: a client can `open` an image once and run the read operations
(`inspect`, `get_plane`, `get_intensity_stats`, `get_thumbnail`, `deposit`)
against the kept-open reader by `handle`.

For operation arguments, the error model, access control, and pixel-data
conventions, see **[service-endpoints.md](service-endpoints.md)**.  For the
design rationale, see **DESIGN.md §9** (deposit) and **§10** (sessions).

## 1. The model: two planes

- **Control plane** — the Unix-domain socket, carrying newline-delimited
  JSON (one object per line, each direction).
- **Data plane** — a **region the client owns**: a file the client
  creates, sizes, and maps (`mmap`) into its own address space, then
  unlinks when done.  Pixel bytes are written into this region and
  **never travel over the socket**.

The server attaches to the region only for the duration of one deposit
(open → write → flush → close) and **never unlinks it**.  Lifecycle —
creation, sizing, mapping, deletion — is entirely the client's.

**Memory-only is the client's choice.** The server writes to whatever
path you give it; it does not decide RAM vs. disk.  To keep the region in
RAM, place it on a `tmpfs` filesystem — on Linux, `/dev/shm`.  If you
point it at an ordinary disk path, the bytes hit disk.  (Either works;
only residency differs.)

## 2. Starting the server

```sh
jbang runner/bioimage_socket.java --allow /dev/shm --allow /data/microscopy
jbang runner/bioimage_socket.java --socket /run/user/1000/bio.sock --allow /dev/shm
```

Options: `--socket <path>` (default: `$XDG_RUNTIME_DIR/bioimage-deposit.sock`,
or the temp dir if `XDG_RUNTIME_DIR` is unset), and the shared `--allow
<path>` / `--deny <path>` (repeatable).

**You must `--allow` (or otherwise permit) both** the directory of the
source images **and** the directory holding the shared-memory region
(e.g. `--allow /dev/shm`).  A path outside the allow list is rejected
with `access_denied`.

Local development against the fat jar (the published `//DEPS` artifact
won't contain this class yet):

```sh
mill assembly
java -cp out/assembly.dest/out.jar \
    lab.kerrr.mcpbio.bioimageserver.BioImageSocketService --allow /dev/shm --allow /data
```

The server logs `listening on <socket path>` to stderr and removes the
socket file on exit.

## 3. Framing & connection rules

- **Newline-delimited JSON.** Each message is one JSON object encoded as
  UTF-8 and terminated by `\n`.  Do not embed raw newlines inside a
  message.
- **Sequential.** At most **one deposit in flight per connection**.  Wait
  for the `filled` (or `error`) reply before sending the next request.
  Sending a second deposit while one is in flight returns an
  `invalid_argument` error.
- **Reuse.** A connection may serve any number of deposits, one after
  another.
- **`id`.** Every request may carry a client-chosen `id` (string); the
  server echoes it on the matching reply.  Recommended for correlation.
- **The server may vanish at any time.** Like any service, it can crash,
  be killed, or be shut down.  A robust client must tolerate the
  connection closing at any point, including losing the last bytes of a
  reply — see §8.

## 4. Handshake

On connect, the server immediately sends a hello:

```json
{"type":"ready","protocol":1,"service":"bioimage-socket","version":"0.2.0"}
```

`protocol` is the wire-protocol version (currently `1`).  Read this line
before sending anything.

## 5. The deposit flow

A typical, robust sequence:

1. **Size it** — send a `deposit` with `"dry_run": true` and no `target`.
   The server replies `filled` with the descriptor, including
   `total_bytes`.  (Nothing is written.)
2. **Allocate the region** — create and size the file to at least
   `total_bytes` (e.g. on `/dev/shm`).  The client owns this file.
3. **Deposit** — send the same selection with a `target` pointing at the
   region and its `capacity_bytes`.
4. **Read `filled`** — once it arrives, every byte is written and
   flushed; the region is ready.
5. **Interpret** — map the region and index it using the descriptor
   (§7).
6. **Clean up** — unlink the region when done.  Reuse the connection for
   the next deposit, or close it.

You can skip the dry run if you already know the exact size (e.g. from an
`inspect_image` call on another transport).

### Deposit request

```json
{"type":"deposit","id":"c1",
 "path":"/data/stack.czi","series":0,
 "channels":"0",                       // slice; ":" = all, "0,2" = a list
 "z":":",                              // ":" = all Z (one whole volume)
 "t":"0",                              // single timepoint
 "target":{"kind":"file",
           "path":"/dev/shm/bio-7f3a",
           "capacity_bytes":43352064},
 "timeout_seconds":60}
```

- `channels`, `z`, and `t` are **slice selections** (service-endpoints.md
  §2) and are **required** — there is no implicit "all". Write `":"` for a
  full dimension, single indices for one plane, or lists like `"0,2"` /
  `"4:9,11:"`. A deposit therefore can never silently grab the whole
  dataset by omission.
- `target.kind` defaults to `"file"` (the only kind today).
- `target.capacity_bytes` is **required** and is the size you allocated.
  The server computes `required = T·Z·C·Y·X·bytes_per_sample`; if
  `required > capacity_bytes` it replies `invalid_argument` and **writes
  nothing** — a short buffer is never partially filled.
- `dry_run: true` returns the descriptor without a `target` and without
  writing.

### Filled reply (success)

```json
{"type":"filled","id":"c1",
 "offset":0,"total_bytes":43352064,"plane_bytes":688128,
 "pixel_type":"uint16","bytes_per_sample":2,"signed":false,"little_endian":true,
 "axis_order":["t","c","z","y","x"],
 "shape":{"x":672,"y":512,"c":1,"z":21,"t":1}}
```

`filled` **is** the ready signal: no `filled` ⇒ the region is incomplete
and must be discarded.

## 5b. Sessions and handle-based reads

To work with one image repeatedly without re-opening and re-parsing it each
time, `open` a session and address later operations by its `handle`.

```json
{"type":"open","id":"o1","path":"/data/stack.czi","series":0}
→ {"type":"session_opened","id":"o1","handle":"e2b1…","summary":{ …SUMMARY metadata… }}
```

Then any read operation may carry `handle` **instead of** `path`:

```json
{"type":"inspect","id":"i1","handle":"e2b1…","detail":"full"}
→ {"type":"inspected","id":"i1","result":{ …full metadata… }}

{"type":"get_ome_metadata","id":"x1","handle":"e2b1…"}
→ {"type":"ome_metadata","id":"x1","format":"ome_xml","content":"<OME …>"}

{"type":"get_plane","id":"p1","handle":"e2b1…","channel":"0","z":"0","t":"0"}
→ {"type":"plane","id":"p1","png_base64":"iVBOR…"}

{"type":"get_intensity_stats","id":"s1","handle":"e2b1…","channels":":","z":":","t":":"}
→ {"type":"stats","id":"s1","result":{ … }}

{"type":"get_thumbnail","id":"th1","handle":"e2b1…","channels":":"}
→ {"type":"thumbnail","id":"th1","projection_used":"max_intensity","png_base64":"iVBOR…"}

{"type":"deposit","id":"d1","handle":"e2b1…","channels":"0","z":":","t":"0","target":{…}}
→ {"type":"filled","id":"d1", …descriptor… }

{"type":"close","id":"c1","handle":"e2b1…"}
→ {"type":"closed","id":"c1","handle":"e2b1…"}
```

Notes:
- **`handle` or `path`.** Every read op accepts either; with `handle` it reuses
  the session's open reader, with `path` it is stateless (as on HTTP/MCP).
- **Result framing.** JSON ops reply with the value under `result`
  (`inspected`/`stats`).  PNG ops reply with the image base64-encoded in
  `png_base64` (`plane`/`thumbnail`; thumbnail also reports `projection_used`).
  Base64 over the line is a convenience — for raw pixel volumes prefer
  `deposit` into shared memory.
- **Lifetime.** A handle is owned by this connection.  `close` releases it; if
  the connection drops, every session it opened is closed (its reader
  released) automatically.  Operations on one handle are serialized server-side
  (Bio-Formats readers are not thread-safe).
- **Synchronicity.** `open`/`close` and the JSON/PNG reads are answered inline
  and in order; only `deposit` is asynchronous (one in flight per connection),
  so the connection stays responsive to a disconnect during a long fill.

## 6. Errors

```json
{"type":"error","id":"c1","error_kind":"invalid_argument","message":"..."}
```

`error_kind` is one of `access_denied`, `invalid_argument`, `timeout`,
`io_error` (the kinds from service-endpoints.md §4), or `shutdown_refused`
(§9).  An error ends that request; the connection stays open.

## 7. Interpreting the region

Pixels are written **C-order, X fastest, T slowest**, in the **NGFF /
OME-Zarr canonical order** `axis_order = ["t","c","z","y","x"]` (the order
napari/zarr/dask expect, so the region maps in without a transpose).  The
sample at `(t,c,z,y,x)` — indices relative to the **deposited selection**,
not the source file — is at byte

```
offset + ((((t*C + c)*Z + z)*Y + y)*X + x) * bytes_per_sample
```

where `X,Y,C,Z,T` are `shape.x/y/c/z/t`.  Each contiguous `plane_bytes`
block (`X*Y*bytes_per_sample`) is one row-major `(t,c,z)` plane, and each
channel's full Z-stack is a contiguous (channel-major) block.  Multi-byte
samples use the byte order given by `little_endian`.

NumPy, mapping the region read-only:

```python
import numpy as np

d = filled  # the descriptor dict
endian = "<" if d["little_endian"] else ">"
base = {"int8":"i1","uint8":"u1","int16":"i2","uint16":"u2",
        "int32":"i4","uint32":"u4","float":"f4","double":"f8",
        "bit":"u1"}[d["pixel_type"]]
dtype = np.dtype(base if len(base) == 2 and base[1] == "1" else endian + base)

s = d["shape"]
arr = np.memmap(region_path, dtype=dtype, mode="r",
                offset=d["offset"], shape=(s["t"], s["c"], s["z"], s["y"], s["x"]))
# e.g. the first channel of the first volume:
volume = arr[0, 0, :, :, :]          # (z, y, x)
```

A "volume" is just a deposit with a full Z-range and a single
channel/timepoint; there is no native multi-plane read underneath, so the
server loops planes and lays them out contiguously for you.

## 8. Robustness

- **Reply == ready.** Treat the absence of a `filled` as failure and
  discard the region.
- **Drop on disconnect.** If you close the connection mid-deposit, the
  server promptly cancels the fill and closes its handle on the region;
  it never unlinks it.  The partially-written region is yours to discard.
- **Lost tail bytes.** Because the server can disappear at any moment, a
  client must already handle the connection closing unexpectedly — losing
  the final bytes of a reply (including after a shutdown request) is just
  one case of that, not a special one.

## 9. Shutdown

A client may ask the server to exit, honored **only when the requester is
the sole connected client** — so one client can never tear the server out
from under others:

```json
{"type":"shutdown","id":"s1"}
→ {"type":"shutdown_ok","id":"s1"}        // then the server exits
→ {"type":"error","id":"s1","error_kind":"shutdown_refused",
   "message":"refusing shutdown: N other client(s) connected"}
```

After `shutdown_ok`, the server closes the listener, removes the socket
file, and exits; you will see the reply followed by EOF.

## 10. Complete worked client (Python)

A minimal, dependency-free client that sizes, allocates, deposits, reads
the region, and cleanly shuts the server down.

```python
import socket, json, os

SOCK   = os.path.expandvars("$XDG_RUNTIME_DIR/bioimage-deposit.sock")
SOURCE = "/data/stack.czi"
REGION = "/dev/shm/bio-region-demo.bin"

s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.connect(SOCK)
f = s.makefile("rwb")                       # NB: makefile dups the fd

def send(obj): f.write((json.dumps(obj) + "\n").encode()); f.flush()
def recv():    return json.loads(f.readline())

hello = recv()
assert hello["type"] == "ready" and hello["protocol"] == 1

# Select one channel-0 volume (all Z, timepoint 0).
sel = {"path": SOURCE, "channels": "0", "z": ":", "t": "0"}

# 1) dry run to learn the size
send({"type": "deposit", "id": "size", **sel, "dry_run": True})
d = recv()
assert d["type"] == "filled", d
total = d["total_bytes"]

# 2) allocate the region (client owns it; tmpfs ⇒ RAM)
with open(REGION, "wb") as g:
    g.truncate(total)

# 3) deposit for real
send({"type": "deposit", "id": "fill", **sel,
      "target": {"kind": "file", "path": REGION, "capacity_bytes": total}})
filled = recv()
assert filled["type"] == "filled", filled

# 4) interpret (see §7 for the NumPy version)
data = open(REGION, "rb").read()
assert len(data) == filled["total_bytes"]

# 5) clean up our region, then shut the server down (we're the only client)
os.unlink(REGION)
send({"type": "shutdown", "id": "bye"})
print(recv())                               # {"type": "shutdown_ok", ...}

# Closing both the makefile and the socket fully drops the connection.
f.close(); s.close()
```
