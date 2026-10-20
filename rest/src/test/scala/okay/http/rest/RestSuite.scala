package okay.http.rest

import okay.effects.{Sum, Void}
import okay.effects.json.Json
import okay.effects.json.Json.{Str, num, obj}
import okay.effects.machine.Machine
import okay.effects.shape.{Problem, Shape}
import okay.effects.shape.Shape.{field, int, record, text}
import okay.http.{Http, Https, Request, Response}

class RestSuite extends munit.FunSuite {
  val rests: Rests[Machine.type] = new Rests[Machine.type]()(Machine)
  val https: Https[Machine.type] = new Https[Machine.type]()(Machine)
  import Machine._
  import rests._
  type H[X] = Sum[Void, Http, X]
  final case class Who(name: String, age: Int)
  val who: Shape[Who] = record(field("name", text), field("age", int))(Who(_, _))(w => (w.name, w.age))

  test("paths: literals, parameters, the rest; rendered back") {
    assertEquals(Path("/records/:id/versions").matches("/records/a%20b/versions"), Some(Map("id" -> "a b")))
    assertEquals(Path("/records/:id").matches("/records"), None)
    assertEquals(Path("/files/*").matches("/files/a/b/c"), Some(Map("*" -> "a/b/c")))
    assertEquals(Path("/records/:id").render(Map("id" -> "a b")), "/records/a%20b")
  }
  test("routes dispatched: a match, a 405 with Allow, a 404 as a problem; a body read by a shape") {
    val routes: List[Route[H]] = List(
      get[H]("/who/:id") { (ps, _) => pure[Response, H](Resource(obj("id" -> Str(ps("id"))), List(Link("self", s"/who/${ps("id")}"), Link("rename", s"/who/${ps("id")}", "POST", Some("Rename"), Some(okay.effects.shape.JsonSchema.of(who.erased))))).response()) },
      post[H]("/who/:id") { (ps, req) => pure[Response, H](body(req, who).fold(identity, w => Response.json(obj("id" -> Str(ps("id")), "name" -> Str(w.name))))) })
    val (responses, _) = Machine.run(https.scripted(List(Request.get("/who/7"), Request.json("POST", "/who/7", obj("name" -> Str("Ann"), "age" -> num(41))),
      Request.json("POST", "/who/7", obj("name" -> Str("Ann"))), Request.post("/who/7", "{nope"), Request("DELETE", "/who/7"), Request.get("/none")))(https.serve[H](dispatch[H](routes))))
    assertEquals(responses.map(_.status), List(200, 200, 400, 400, 405, 404))
    assert(responses.head.body.contains("\"rel\":\"rename\"") && responses.head.body.contains("\"schema\":{"))
    assertEquals(responses(1).body, """{"id":"7","name":"Ann"}""")
    assert(responses(2).body.contains("\"path\":[\"age\"],\"message\":\"required\""), responses(2).body)
    assertEquals(responses(2).headers("Content-Type"), "application/problem+json")
    assertEquals(responses(4).headers("Allow"), "GET, POST")
  }
}
