# media-service

Receives uploaded files (images, video, audio, voice notes, documents), checks them, stores them in an S3-compatible object storage, makes thumbnails for pictures, and hands out short-lived signed download links. API: `API_DOCUMENTATION.md` sections 5 (Phase 5) and 6, internal endpoints in section 8. Design: `ARCHITECTURE.md` section 3d.

- Port: 8205 | Database: `media_db` (user `media_user`)
- Uses: MySQL (metadata only) and an object storage with the S3 API (RustFS locally, AWS S3 in production). **No Kafka, no Redis** in this service.
- Build and test: `mvn test` (needs Docker: Testcontainers starts MySQL and a real RustFS)
- Run (IDE mode, from this folder): `mvn spring-boot:run -Dspring-boot.run.profiles=dev` (infrastructure from `infra/docker-compose.infra.yml`)
- Docker image: `docker build -t chat-media-service .`
- Swagger UI: http://localhost:8205/swagger-ui.html

## How an upload is checked (in this order)
1. `purpose` (IMAGE, VIDEO, DOCUMENT, AUDIO, VOICE), optional `durationSeconds` 0-7200, the file is not empty
2. size limit of that kind (413 `FILE_TOO_LARGE`)
3. the file is copied to a temporary file on disk (a 100 MB video never sits in memory) and **what it really is** is read from its first bytes (`FileTypeDetector`). The name and the browser's Content-Type are never trusted.
4. the real type must be allowed for the purpose (415 `UNSUPPORTED_TYPE`) and the file name and claimed Content-Type must fit it (415 `CONTENT_MISMATCH`)
5. images: size read from the header **without decoding** (refuses "decompression bombs": more than 12000 px per side or 50 million pixels), then a thumbnail is made (JPEG, at most 320 px; this also proves the image can be read, otherwise 400 `INVALID_IMAGE`)
6. `VirusScanner` hook (see below)
7. the object (and the thumbnail) are stored under a random key `yyyy/MM/<uuid>` and the rows are saved. If saving fails the objects are removed again.

Accepted: JPEG PNG GIF WebP (images); MP4 MOV WebM (video); MP3 M4A OGG WAV WebM (audio); the same audio types for voice notes; PDF DOCX XLSX PPTX plain text CSV (documents). Refused whatever they are called: programs, scripts, HTML, SVG, XML, archives, Matroska, AVI, HEIC. Office files are recognised by reading the zip's list of names only (never unpacked); zips with more than 2000 entries are refused.

Limits (`chatapp.media.limits-mb`): image 10 MB, video 100 MB, document 25 MB, audio and voice 25 MB. The outer wall is 100 MB per file (Spring multipart); nginx and the gateway are configured for it separately.

## Who may do what
- **Uploader** (token `sub`): upload, look at their own file (`GET /api/media/{id}`, signed links), delete it while no message uses it. Everybody else gets 404.
- **message-service** (header `X-Internal-Token`, `/internal/**`, never routed by the gateway): `attach` a file to a message and ask for a `download-link`. It decides who may see a message; this service does not know about conversations.
- **status-service** (Phase 8, same internal API): `attach` status photos / videos with the reference `status:<ownerId>:<idempotencyKey>`, ask for a `download-link` (it decides who may see a status), and `POST /internal/media/{id}/release` body `{ ownerId, reference }` when the status expires or is deleted: the file is deleted (row `DELETED`, objects removed, access-log action `RELEASE`) **only** when `ownerId` uploaded it and `reference` is exactly the attach reference, so a message's file can never be released. Already deleted: 200 (safe to retry). Anything else: 404.
- Attaching is atomic and idempotent: one conditional `UPDATE ... WHERE status = 'UPLOADED'`, so two parallel attaches with different references have exactly one winner (the other gets 409 `ATTACHMENT_ALREADY_USED`); the same reference again is fine. The loser looks at the real current row with a locking read.

## Signed links
The service uploads through `S3_ENDPOINT` (inside Docker `http://s3:9000`) but signs links for `S3_PUBLIC_ENDPOINT` (`http://localhost:9010`), the address the **browser** can reach. Links last 5 minutes and force `response-content-type` (the detected type) and `response-content-disposition` (documents: attachment, everything else: inline). Signing is a local calculation, so the service does not need to reach the public address itself.

## Cleanup
`OrphanCleanupJob` runs hourly (`chatapp.media.cleanup.interval-ms`) and deletes uploads still `UPLOADED` after 24 hours (`max-age-hours`): objects removed, row marked `DELETED`, `EXPIRE` written to the access log. Files used by a message are never touched.

## Environment variables
Defaults in `application.yml` work in IDE mode; the `prod` profile has no localhost defaults.

| Variable | Meaning |
|---|---|
| `MEDIA_DB_URL`, `MEDIA_DB_USERNAME`, `MEDIA_DB_PASSWORD` | MySQL |
| `JWT_SECRET` | at least 32 characters, same value in every service |
| `INTERNAL_SERVICE_TOKEN` | at least 24 characters, same value in every service |
| `S3_ENDPOINT` | storage address used by this service (Docker: `http://s3:9000`) |
| `S3_PUBLIC_ENDPOINT` | storage address put into signed links, reachable by browsers (`http://localhost:9010`) |
| `S3_ACCESS_KEY`, `S3_SECRET_KEY` | storage credentials |
| `S3_REGION` (us-east-1), `S3_BUCKET` (chat-media), `S3_CREATE_BUCKET` (true) | bucket settings; set `S3_CREATE_BUCKET=false` when the bucket is created by infrastructure |
| `VIRUS_SCANNER` | `none` (default, nothing scanned) or `clamav` |
| `CLAMAV_HOST`, `CLAMAV_PORT` | the ClamAV daemon (`clamav`, 3310) when `VIRUS_SCANNER=clamav` |
| `SWAGGER_ENABLED` | true / false |

The bucket is created in the background at startup (retried for about a minute while the storage starts) and again at the first upload if needed.

## Not done on purpose (see the checklist)
- **Malware scanning** (Phase 9): with `VIRUS_SCANNER=clamav` every upload goes to ClamAV (`ClamAvVirusScanner`, clamd INSTREAM) before it is stored, fail closed (503 when ClamAV is down). Without it `NoOpVirusScanner` accepts everything and logs a warning at startup (development). The old note follows: provide a real `VirusScanner` (for example a ClamAV client) and remove the no-op class **before production**.
- Video thumbnails and durations (need ffmpeg); the duration of audio/video is whatever the client sends. WebP images have no thumbnail (Java cannot decode WebP). Original files are not re-compressed.
- A CMYK JPEG (or any image Java cannot decode) is refused with `INVALID_IMAGE`.
- Profile and group photos (they need long-lived public image links).
- `MediaUploadedEvent` (nobody consumes it yet).
- Signed links stay valid for 5 minutes even if the message is deleted or the user leaves the group in that time.

## Code map
```
controller/   MediaController (public API), InternalMediaController (for message-service and status-service)
service/      MediaUploadService (the checks above), MediaService (get / delete / attach / links), OrphanCleanupJob
validation/   FileTypeDetector (magic numbers), MediaRules (what is allowed), ImageInspector (size + thumbnail), VirusScanner
storage/      StorageService (interface) and S3StorageService (AWS SDK v2: client + presigner)
util/         FileNameCleaner, ContentDispositions
```
