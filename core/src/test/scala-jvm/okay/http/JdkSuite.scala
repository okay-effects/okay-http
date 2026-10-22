package okay.http

import okay.effects.{Sum, Void}
import okay.effects.json.Json
import okay.effects.machine.Machine

/** THE JDK'S SERVER AND CLIENT, for real, on a free port: the service served from a thread, called by the client */
class JdkSuite extends munit.FunSuite {
  val https: Https[Machine.type] = new Https[Machine.type]()(Machine)
  val jdk: JdkHttp[Machine.type] = new JdkHttp[Machine.type]()(Machine)
  import Machine._
  import https._
  type H[X] = Sum[Void, Http, X]
  type C[X] = Sum[Void, Fetch, X]
  def service(req: Request): Response ! H = pure[Response, H](req match {
    case Request("GET", "/hello", q, _, _) => Response.text(s"hello, ${q.get("name").flatMap(_.headOption).getOrElse("nobody")}")
    case Request("POST", "/echo", _, _, body) => Response.ok(body, "application/json")
    case other => Response.notFound(other.path)
  })
  test("a request over the wire, the response back; stop ends the loop") {
    val server = JdkServer.start()
    val serving = new Thread(() => Machine.run(jdk.jdk(server)(serve[H](service))))
    serving.start()
    val base = s"http://127.0.0.1:${server.port}"
    val (a, b, c) = Machine.run(jdk.jdkClient()(get[C](s"$base/hello?name=Wire").flatMap(a => postJson[C](s"$base/echo", Json.obj("k" -> Json.Str("v"))).flatMap(b => get[C](s"$base/missing").map(c => (a, b, c))))))
    assertEquals((a.status, a.body), (200, "hello, Wire"))
    assertEquals((b.status, b.body), (200, """{"k":"v"}"""))
    assertEquals(c.status, 404)
    assert(a.headers.exists { case (k, v) => k.equalsIgnoreCase("content-type") && v.startsWith("text/plain") })
    server.stop(); serving.join(5000)
    assert(!serving.isAlive)
  }
}
