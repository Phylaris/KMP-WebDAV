# webdav-client-kmp

A WebDAV (RFC 4918) client library written in Kotlin Multiplatform, sharing 100% of
its protocol logic between **Android**, **iOS**, **JVM** and the **browser**
(Kotlin/JS and Kotlin/Wasm). It is a library (no UI)
meant to be embedded in other apps and projects.

## Features

- **Core file operations**: list directories (`PROPFIND`), download (`GET`), upload (`PUT`),
  delete (`DELETE`), create collections (`MKCOL`), move/copy (`MOVE`/`COPY`), read and
  modify properties (`PROPPATCH`).
- **Advanced**: byte-range downloads (resume support), exclusive write locks
  (`LOCK`/`UNLOCK`), recursive listing with bounded concurrency, HTTP redirects handled
  manually (bodies and methods survive 3xx).
- **Authentication**: HTTP Basic, HTTP Digest (RFC 7616), and Bearer token (OAuth2)
  through Ktor's Auth plugin.
- **Streaming**: uploads and downloads are streamed; large files never sit in memory.
  Progress callbacks report `(bytesTransferred, totalBytes)`.
- **Fault tolerant parsing**: unknown properties/namespaces are preserved, malformed
  entries never break a whole listing.

## Supported platforms

| Target | Engine | Artifact |
|---|---|---|
| Android (minSdk 24) | OkHttp | `com.sylvandale.webdav.client` AAR |
| iOS (arm64, simulator) | Darwin (NSURLSession) | `WebDavClient` XCFramework |
| JVM | CIO | JVM jar |
| Browser JS / Wasm | Js (browser fetch) | JS library / Wasm library |

The JVM target also serves as the test host: `./gradlew :jvmTest` runs the shared
suite (XML parsing golden cases + protocol behavior via Ktor's `MockEngine`). The
same suite runs in the browser: `./gradlew :jsBrowserTest :wasmJsBrowserTest`
(Chrome headless).

## Tech stack

- Kotlin 2.4.x, Kotlin Multiplatform (Android / iOS / JVM / JS / Wasm targets)
- Ktor Client 3.5.x (`io.ktor:ktor-client-*`) — note that Ktor 3.5 moved
  `bodyAsText`/`bodyAsChannel` to `io.ktor.client.statement`, removed the WebDAV
  `HttpMethod` constants and `buildHeaders`, and switched `ByteReadPacket` to
  `kotlinx.io.Source`
- xmlutil 0.91.x (`io.github.pdvrieze.xmlutil`) for multi-status XML parsing
- kotlinx.serialization / kotlinx.coroutines

## Getting started

### Kotlin / Android

```kotlin
val client = WebDavClient.create(
    baseUrl = "https://example.com/remote.php/dav/files/alice/",
    auth = Auth.Basic(username = "alice", password = "secret"),
)
```

### Swift / iOS

```swift
let client = WebDavClientKt.WebDavClient.create(
    baseUrl: "https://example.com/remote.php/dav/files/alice/",
    auth: Auth.Basic(username: "alice", password: "secret")
)
```

## Usage

```kotlin
val dav = WebDavClient.create(baseUrl, auth = Auth.Basic("u", "p"))

// List a directory
val files: List<WebDavFile> = dav.list("docs")

// Download (streamed)
dav.downloadToChannel("docs/big.bin", sink) { transferred, total ->
    println("$transferred / $total")
}

// Upload (streamed)
dav.uploadFromChannel("docs/backup.bin", channel, contentLength = size) { transferred, total ->
    println("$transferred / $total")
}

// Mutations
dav.mkdir("new folder")
dav.move("a.txt", "b/a.txt")
dav.copy("docs", "docs-copy")
dav.delete("old.txt")
dav.setProperties("f.txt", set = mapOf(PropertyName.DISPLAYNAME to "renamed"))

// Locking
val lock = dav.lock("f.txt", owner = "my-app", timeout = 5.minutes)
dav.unlock("f.txt", lock.token)

// Resume a download
dav.downloadToChannel("big.bin", sink, range = downloadedBytes..Long.MAX_VALUE)
```

### Custom properties

Read arbitrary (dead) properties returned by the server:

```kotlin
val favorite = file.properties[PropertyName("http://owncloud.org/ns", "favorite")]?.text
```

## Browser notes

When embedding the library in a web app, keep in mind:

- **CORS**: browsers enforce same-origin policy. The WebDAV server must answer CORS
  preflight (`OPTIONS`) requests and allow the custom methods (PROPFIND, MKCOL,
  LOCK, ...) and headers (Depth, Destination, Lock-Token, ...) the client sends.
- **User-Agent**: `User-Agent` is a forbidden header in browsers; setting it is
  silently ignored, but the browser always sends its own UA string, so servers that
  reject UA-less requests (e.g. fnos) keep working.
- **Memory**: the JS/Wasm engine is fetch-based; `ReadChannelContent` uploads may
  be buffered fully in memory, so very large uploads use more memory than on JVM.

## Paths

All `path` arguments are logical paths relative to the client base URL, without a
leading slash, e.g. `"docs/report 2026.txt"`. The library handles URL encoding,
decoding of server `href` values, and normalizes base URLs (trailing slash).

## Redirects

The library disables Ktor's automatic redirect handling and follows 3xx responses
itself: methods and request bodies are preserved on redirect (301/302/307/308), and
303 responses switch to `GET`. If you supply your own `HttpClient`, keep
`followRedirects = false`.

## Exceptions

All failures derive from `DavException`:

- `HttpStatusException` and subclasses: `UnauthorizedException` (401),
  `ForbiddenException` (403), `NotFoundException` (404), `ConflictException` (409),
  `PreconditionFailedException` (412), `LockedException` (423), ...
- `DavProtocolException`: unparseable server responses (malformed XML, missing
  lock token, partial PROPPATCH failures).

## Development

```bash
./gradlew :jvmTest          # run shared tests on the JVM host
./gradlew :jsBrowserTest    # run shared tests in the browser (Kotlin/JS)
./gradlew :wasmJsBrowserTest  # run shared tests in the browser (Kotlin/Wasm)
./gradlew :assembleDebug    # build the Android AAR
./gradlew :linkDebugFrameworkIosArm64  # build the iOS framework (macOS only)
```

The shared test suite covers XML parsing golden cases and protocol behavior using
Ktor's `MockEngine` (no live server needed).
