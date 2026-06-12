# BioImage HTTP API

How a client talks to the plain-HTTP microservice
(`BioImageHttpService`).  For operation arguments, the error model, and
access control, see **[service-endpoints.md](service-endpoints.md)**.

This transport exposes the five image operations.  It does **not** expose
`deposit` (shared-memory transfer is the socket transport — see
[API-socket.md](API-socket.md)).

## 1. Starting the server

Via the JBang runner (end users):

```sh
jbang runner/bioimage_http.java --allow /data/microscopy
# default port 8722
jbang runner/bioimage_http.java --port 9000 --allow /data/microscopy
```

Options: `--port <n>` (default **8722**; a second local user picks another),
`--bind <addr>` (restrict to one interface, e.g. `127.0.0.1`; default is all
interfaces), `--require-token` (demand an auth token on the operation
endpoints), and the shared `--allow <path>` / `--deny <path>` (repeatable).
You can also hardcode these in the runner file.

For local development against a build that contains not-yet-published
classes, run the class directly from the fat jar (avoids the published
`//DEPS` artifact shadowing local classes on jbang's classpath):

```sh
mill assembly
java -cp out/assembly.dest/out.jar \
    lab.kerrr.mcpbio.bioimageserver.BioImageHttpService --port 8722 --allow /data
```

By default the server binds **all interfaces** (`0.0.0.0:<port>`) — HTTP is the
exposed transport — and logs the bound host/port to stderr.  `--bind 127.0.0.1`
restricts it to loopback.

## 1a. Authentication (opt-in)

HTTP is **open by default** (no token — that is its point).  Pass
`--require-token` to demand a shared token on the operation endpoints:

- The server prints the token to stderr at startup (`bioimage-http: auth token:
  …`) and also writes it, with the port, to a per-user 0600 descriptor file
  (`$XDG_RUNTIME_DIR/bioimage-http.json`) for same-user local clients.
- Send it on each operation request as `Authorization: Bearer <token>` (or
  `X-Auth-Token: <token>`).  A missing/wrong token returns **401** with the
  standard `{"error_kind":"access_denied", …}` body.
- `GET /` and `GET /health` stay **unauthenticated** so liveness checks work.

For real network exposure, put the server behind your own TLS-terminating
reverse proxy; the token is a coarse gate, not a substitute for transport
security.

## 2. Request shape

Every operation is a `POST` to `/<operation>` whose body is the
operation's argument object as JSON (the snake_case parameters from
service-endpoints.md §3).  Authentication is off unless `--require-token` is
given (§1a).

```
POST /<operation>  HTTP/1.1
Content-Type: application/json

{ "path": "/abs/file.czi", ... }
```

The `path` (and `output_path` for export) must be **absolute** and under
an allowed directory.

## 3. Endpoints

| Method & path             | Success response                          |
|---------------------------|-------------------------------------------|
| `GET  /`                  | `application/json` — service info / health |
| `POST /inspect_image`     | `application/json` — metadata             |
| `POST /get_intensity_stats` | `application/json` — statistics         |
| `POST /export_to_tiff`    | `application/json` — export result        |
| `POST /get_plane`         | `image/png` — raw grayscale PNG bytes     |
| `POST /get_thumbnail`     | `image/png` — raw RGB PNG bytes           |

**Image endpoints return the PNG bytes directly** (not base64).  For
`get_thumbnail`, the projection actually used is returned in the
`X-Projection-Used` response header (e.g. `max_intensity`), since the
body is pure image data.

`GET /` returns a small JSON object:

```json
{
  "service": "bioimage-http",
  "version": "0.1.1",
  "status": "ok",
  "endpoints": ["POST /inspect_image", "POST /get_thumbnail",
                "POST /get_plane", "POST /get_intensity_stats",
                "POST /export_to_tiff"]
}
```

## 4. Errors

Failures return a JSON body and an HTTP status by error kind:

| Condition                          | Status | `error_kind`         |
|------------------------------------|:------:|----------------------|
| path not permitted / unresolvable  | 403    | `access_denied`      |
| bad/missing argument               | 400    | `invalid_argument`   |
| malformed JSON request body        | 400    | `invalid_argument`   |
| operation timed out                | 504    | `timeout`            |
| file not found / unreadable / I/O  | 502    | `io_error`           |
| wrong method (e.g. GET an op)      | 405    | `method_not_allowed` |

```json
{ "error_kind": "access_denied",
  "message": "Path is not under any allowed directory or client root" }
```

## 5. Examples

Inspect (summary):

```sh
curl -s -X POST http://127.0.0.1:8722/inspect_image \
  -d '{"path":"/data/stack.czi","detail":"summary"}'
```

Single plane as PNG (channel 0, downsampled), saved to a file:

```sh
curl -s -X POST http://127.0.0.1:8722/get_plane \
  -d '{"path":"/data/stack.czi","channel":0,"max_size":512}' \
  -o plane.png
```

Thumbnail (all channels), capturing the projection header:

```sh
curl -s -D - -o thumb.png -X POST http://127.0.0.1:8722/get_thumbnail \
  -d '{"path":"/data/stack.czi","channels":":","max_size":256}' \
  | grep -i x-projection-used
```

Intensity stats over the first 10 timepoints of channel 0 (all Z):

```sh
curl -s -X POST http://127.0.0.1:8722/get_intensity_stats \
  -d '{"path":"/data/movie.tif","channels":"0","z":":","t":"0:10"}'
```

(Selections are slices — see [service-endpoints.md](service-endpoints.md)
§2. `channels`/`z`/`t` are required; use `":"` for all.)

Export a Z-subset to OME-TIFF:

```sh
curl -s -X POST http://127.0.0.1:8722/export_to_tiff \
  -d '{"path":"/data/stack.czi","output_path":"/data/out.ome.tif",
       "channels":":","z":"0:11","t":":","compression":"lzw"}'
```

## 6. Concurrency notes

Requests run on a virtual-thread-per-task executor; each request gets its
own Bio-Formats reader (readers are not thread-safe and are never
shared), so concurrent requests are safe.  Large files subsample or time
out rather than hanging — check JSON responses for notes about
subsampled data.
