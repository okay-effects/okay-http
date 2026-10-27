# okay-http — HTTP as effects, and REST as a style over it

`core`: a `Request` and a `Response` as data; `Http` — a request received, a response given — the server's effect, and
`Fetch` the client's; their handlers the JDK's server and client on the JVM (no dependency), a script of requests, a
loopback for a client and a server in one process. `rest`: paths with parameters, routes dispatched to programs, JSON
bodies read by a `Shape` with the problems as a 400, links and resources as data — a representation with its
affordances — and troubles in RFC 9457's shape. On [okay-simple](https://github.com/okay-effects/okay-simple) alone, for
Scala 3 and 2.13. The REST and HTML faces of [okay-faces](https://github.com/okay-effects/okay-faces) stand on it.

```scala-sbt
resolvers ++= Seq("okay-effects" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-http" at "https://okay-effects.github.io/okay-http/maven")
libraryDependencies += "io.github.okay-effects" %% "okay-http-rest" % "0.1.1"    // and okay-http, the transport, with it
```

`%%%` for Scala.js and Scala Native. Releases: [RELEASING.md](RELEASING.md).

A body is text (`body`) — or, when it is not UTF-8 (an image, an upload, a file), `bytes`: then `bytes` is the body and
`body` is empty. `content` is the body as `Bytes` either way; `withContent(bytes)` and `Response.binary(bytes, type)`
make one; the servers and clients keep each as it came. `Fetch.Send(base, request)` (`send`) sends any request, its
body text or bytes; `Fetch.Call` stays for text.

In a page (Scala.js), `Browser.fetch()` is the client: a `Request` sent by the browser's `fetch`, the `Response` back as a `Future` — the transport okay-faces' remote face talks through.
