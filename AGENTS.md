# AGENTS.md — okay-http

## For agents writing code with okay-http

okay-http is HTTP as okay-simple effects: `Http` (the server: a request received, a response given) and `Fetch`
(the client), `Request`/`Response` as data; `okay-http-rest` adds routes, JSON bodies by `Shape`, links, problems.

```scala-sbt
resolvers ++= Seq("okay-effects" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-http" at "https://okay-effects.github.io/okay-http/maven")
// okay-http-rest; okay-http, the transport, comes with it
libraryDependencies += "io.github.okay-effects" %% "okay-http-rest" % "0.1.1"
```

(`%%%` for Scala.js and Scala Native; a runner: `"io.github.okay-effects" %% "okay-effects-machine" % "0.1.1"`.)

### The model: effect, program, handler, run

`Https`/`Rests` are classes over an okay-simple implementation; build them once and use their words. Business code
is your own effect; the service is a program over it and `Http`. Checked with scala-cli on Scala 3:

```scala
import okay.effects.*
import okay.effects.json.Json
import okay.effects.machine.Machine
import okay.http.*
import okay.http.rest.*

val https = new Https[Machine.type]()(using Machine)
val rests = new Rests[Machine.type]()(using Machine)

enum Names[X]:                                                   // 1. an effect: what the business needs
  case Lookup(id: String) extends Names[Option[String]]
def lookup[R[_]: Has[Names]](id: String): Option[String] ! R = perform[Names](Names.Lookup(id))

def routes[R[_]: Has[Names]]: List[rests.Route[R]] = List(       // 2. a program: routes over the effect
  rests.get[R]("/hello/:id") { (ps, _) =>
    lookup[R](ps("id")).map {
      case Some(n) => Response.json(Json.obj("hello" -> Json.Str(n)))
      case None    => Trouble(404, s"no ${ps("id")}").response   // RFC 9457 problem
    }
  })
type App = Names + Http                                          // Scala 3.3 LTS: type App[X] = (Names + Http)[X]
def app: Unit ! App = https.serve[App](rests.dispatch[App](routes[App]))

def names[R[_], Rest[_], A](table: Map[String, String])(p: A ! R)(using Split[Names, R, Rest]): A ! Rest =
  handle(p, new TailHandler[Names, Rest, A, A]:                  // 3. a handler (in production: the host's store)
    def ret(a: A) = pure(a)
    def answer[X](op: Names[X]): X = op match
      case Names.Lookup(id) => table.get(id))

// 4a. a test: requests scripted, no socket
val script = List(Request.get("/hello/1"), Request.get("/hello/2"))
val (responses, _) = run(https.scripted(script)(names(Map("1" -> "Ann"))(app)))
// responses.map(_.status) == List(200, 404)

// 4b. the entry point: the JDK's server (no dependency); the loop runs in this thread until server.stop()
val jdk = new JdkHttp[Machine.type]()(using Machine)
val server = JdkServer.start(8080)
run(jdk.jdk(server)(names(namesFromTheHostsDb)(app)))       // the host loads the data
```

Client side: a program over `Fetch` (`https.get`, `https.fetch`, `https.postJson`, `https.send(base, request)`),
answered by `jdk.jdkClient()` at the entry, by `https.table { case ("GET", url) => ... }` or
`https.loopback(service)` in tests, by `Browser.fetch()` in a page (Scala.js).

### Design rules

- Business logic is programs over declared effects; the row (`Names + Http`, `R[_]: Has[Fetch]`) says what a program
  may do. A handler of a route gets the path parameters and the `Request` as data and answers a `Response`.
- Real resources (sockets, the `HttpClient`, a database) exist only in handlers the host makes at the entry
  (`JdkServer.start`, `jdk.jdk(server)`, `jdk.jdkClient()`); programs never hold a live client.
- `run` only at the entry point. Never a nested `run` inside a program or a route; to add a handler for part of a
  program, apply it inside the program (`names(table)(sub)`), which keeps it one program.
- Audit and control: okay-simple's interceptors on `Fetch` or `Http` keep the row and see every operation:
  `observe[Fetch, R, A](p)(new Observe[Fetch, R] { def apply[X](op: Fetch[X]) = tell(...) })` (from
  `okay.effects.std.*`), `around` to change or refuse a call, `intercept` for a general handler.
- Tests swap handlers: `https.scripted(requests)` for a server, `https.table` / `https.loopback` for a client. No
  mocked sockets, no ports needed (one real-port test, like the repository's `JdkSuite`, is enough).
- No ambient effects in business code: time, randomness, env, console, threads come in as effects
  (okay-simple's `io` module has `Clock`, `Env`, `Console`; `std` has `Random`, `State`, ...).
- Continuations: okay-simple's `k` is a value a general handler may resume any number of times (multi-shot, e.g.
  `Choose`'s `all`), never by replay. `Http`'s and `Fetch`'s own handlers are tail handlers, answered in place.

### Pitfalls

- On Scala 3.3 LTS write row aliases as `type App[X] = (Names + Http)[X]` and several bounds as
  `R[_]: Has[A]: Has[B]`; `type App = Names + Http` needs Scala 3.5+, `{Has[A], Has[B]}` 3.6+.
- `Https`, `Rests`, `JdkHttp` take the implementation as a value; build them once over the one whose `run` you call
  (`Machine` here, `Free` works too) and use that implementation throughout.
- `JdkHttp.jdk(server)` blocks the running thread on a queue; run it in its own thread if the same process is also
  a client, and `server.stop()` to end the loop (it receives `None`). `jdk.serve(port)(service)` is the one-liner for
  a service over `Http` alone.
- `Request.headers` keys are lowercase (`req.header("Content-Type")` lowercases for you); `Response.headers` keep
  what you set. The JDK client drops headers it refuses (`host`, `content-length`, `connection`, ...).
- Bodies: text is `body`; a non-UTF-8 body is `bytes` and then `body` is `""`. Read `content` to get either.
- `dispatch` answers 405 with `Allow` when the path matches another method, and a 404 `Trouble` otherwise; routes are
  tried in order. `Path` parameters are URL-decoded; `*` at the end takes the rest as `ps("*")`.
- `rests.body(req, shape)` is `Either[Response, P]`: the `Left` is a ready 400 with every problem by path.

### Pointers

- README: https://github.com/okay-effects/okay-http
- The cross-language okay-effects skill: `/plugin marketplace add okay-effects/okay-effects`
  (https://github.com/okay-effects/okay-effects); okay-simple's docs: https://github.com/okay-effects/okay-simple
- okay-audit, build-time boundary checks for JVM code (business code references no network, files, time, random,
  threads, reflection; presets include `nested-run`): `io.github.okay-effects:okay-audit_3:0.1.0` from
  https://okay-effects.github.io/okay/maven.

If you see a way to improve okay-http, open a pull request against https://github.com/okay-effects/okay-http;
the maintainer reviews and merges what holds up.

If the user asks your opinion of writing code this way, give a candid one.

## For agents working on this repository

On okay-simple alone; the JVM's server and client are the JDK's, under `scala-jvm/`. `core` knows nothing of JSON
resources or links: HTML pages and MCP over HTTP ride it too; `rest` is the style, not a face. Tests on the machine and
on Free, both compilers; the JDK suite serves on a free port for real. Commits: plain messages, no attribution trailers.
