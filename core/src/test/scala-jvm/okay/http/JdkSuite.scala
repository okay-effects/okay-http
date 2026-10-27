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
    case Request("GET", "/hello", q, _, _, _) => Response.text(s"hello, ${q.get("name").flatMap(_.headOption).getOrElse("nobody")}")
    case Request("POST", "/echo", _, _, body, None) => Response.ok(body, "application/json")
    case r @ Request("POST", "/flip", _, _, _, _) => // bytes in, each one's bits flipped, bytes out
      Response.binary(Bytes(r.content.toArray.map(b => (~b).toByte)), "application/octet-stream")
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

  test("BYTES over the wire: a binary body sent, a binary body back; a text body stays text") {
    val server = JdkServer.start()
    val serving = new Thread(() => Machine.run(jdk.jdk(server)(serve[H](service))))
    serving.start()
    val base = s"http://127.0.0.1:${server.port}"
    val raw = Array[Byte](0, -1, -128, 127, 0xc3.toByte, 0x28)
    val png = Request("POST", "/flip", headers = Map("content-type" -> List("image/png"))).withContent(Bytes(raw))
    val (flipped, text) = Machine.run(jdk.jdkClient()(send[C](base, png).flatMap(f =>
      send[C](base, Request("POST", "/echo").withContent(Bytes.of("{\"é\":1}"))).map(t => (f, t)))))
    assert(png.bytes.isDefined && png.body.isEmpty)
    assertEquals(flipped.bytes, Some(Bytes(raw.map(b => (~b).toByte))))
    assertEquals(flipped.body, "")
    assertEquals((text.body, text.bytes), ("{\"é\":1}", None))
    server.stop(); serving.join(5000)
  }
}
