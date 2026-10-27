package okay.http

import okay.effects.{Runner, Sum, Void}
import okay.effects.json.Json
import okay.effects.machine.Machine
import okay.effects.free.Free

class MessageSuite extends munit.FunSuite {
  test("a target split into path and query; the query decoded and encoded; repeated keys") {
    val r = Request.get("/a/b?x=1&y=two%20words&x=3&z")
    assertEquals(r.path, "/a/b"); assertEquals(r.query("x"), List("1", "3")); assertEquals(r.param("y"), Some("two words")); assertEquals(r.param("z"), Some(""))
    assertEquals(Query.parse(Query.print(Map("k" -> List("a b", "é&=")))), Map("k" -> List("a b", "é&=")))
  }
  test("responses as data") {
    assertEquals(Response.json(Json.obj("a" -> Json.num(1))), Response(200, Map("Content-Type" -> "application/json"), """{"a":1}"""))
    assertEquals(Response.redirect("/x").status, 303)
  }
  test("0.1.0's constructors still there: code compiled against them runs") {
    assertEquals(Response(200, Map("a" -> "b"), "x"), Response(200, Map("a" -> "b"), "x", None))
    assertEquals(Request("GET", "/p", Map.empty[String, List[String]], Map.empty[String, List[String]], "b").bytes, None)
    assertEquals(Response(404).status, 404)
  }
  test("a body over the wire: UTF-8 text stays text, anything else is bytes, nothing lost") {
    assertEquals(Bytes.body("é ok".getBytes("UTF-8")), ("é ok", None))
    val raw = Array[Byte](0xc3.toByte, 0x28, -1)
    assertEquals(Bytes.body(raw), ("", Some(Bytes(raw))))
    assertEquals(Response.binary(Bytes(raw)).content, Bytes(raw))
    assertEquals(Request.post("/x", "t").content, Bytes.of("t"))
    assertEquals(okay.http.Fetch.target("https://h/", Request.get("/a?b=1")), "https://h/a?b=1")
  }
}

abstract class HttpChecks(val fx0: Runner, name: String) extends munit.FunSuite {
  val https: Https[fx0.type] = new Https[fx0.type]()(fx0)
  import fx0._
  import https._
  type H[X] = Sum[Void, Http, X]
  type C[X] = Sum[Void, Fetch, X]

  /** a little service: GET /hello?name=, POST /echo */
  def service[R[_]](req: Request): Response ! R = pure[Response, R](req match {
    case Request("GET", "/hello", q, _, _, _) => Response.text(s"hello, ${q.get("name").flatMap(_.headOption).getOrElse("nobody")}")
    case Request("POST", "/echo", _, _, body, None) => Response.ok(body, "application/json")
    case Request("POST", "/size", _, _, _, Some(b)) => Response.text(b.size.toString)
    case other => Response.notFound(other.path)
  })

  test(s"$name: the server loop over a script of requests") {
    val (responses, _) = fx0.run(scripted(List(Request.get("/hello?name=Ann"), Request.post("/echo", """{"a":1}"""), Request.get("/nope")))(serve[H](service[H])))
    assertEquals(responses.map(_.status), List(200, 200, 404))
    assertEquals(responses.head.body, "hello, Ann"); assertEquals(responses(1).body, """{"a":1}""")
  }
  test(s"$name: a client over a loopback to the service, and over a table") {
    val program: (Response, Response) ! C = get[C]("http://x/hello?name=Bo").flatMap(a => postJson[C]("http://x/echo", Json.num(7)).map(b => (a, b)))
    val (a, b) = fx0.run(loopback[Void](service[Void])(program))
    assertEquals(a.body, "hello, Bo"); assertEquals(b.body, "7")
    assertEquals(fx0.run(table { case ("GET", "http://x/hello?name=Bo") => Response.text("hi") }(get[C]("http://x/hello?name=Bo"))).body, "hi")
    assertEquals(fx0.run(table(PartialFunction.empty)(get[C]("http://x/other"))).status, 404)
  }
  test(s"$name: BYTES — a binary body sent through the loopback and a table; text stays text") {
    val raw = Array[Byte](1, -2, 3, -128)
    val program: (Response, Response) ! C =
      send[C]("http://x", Request("POST", "/size").withContent(Bytes(raw))).flatMap(a =>
        send[C]("http://x/", Request("POST", "/echo").withContent(Bytes.of("[1]"))).map(b => (a, b)))
    assertEquals(fx0.run(loopback[Void](service[Void])(program)) match { case (a, b) => (a.body, b.body) }, ("4", "[1]"))
    val img = Response.binary(Bytes(raw), "image/png")
    assertEquals(fx0.run(table { case ("GET", "http://x/i.png?v=2") => img }(send[C]("http://x",
      Request.get("/i.png?v=2")))).content, Bytes(raw))
  }
}
class HttpOnMachine extends HttpChecks(Machine, "machine")
class HttpOnFree extends HttpChecks(Free, "free")
