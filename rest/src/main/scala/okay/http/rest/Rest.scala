package okay.http.rest

import okay.effects.Effects
import okay.effects.json.Json
import okay.effects.shape.{Problem, Problems, Shape}
import okay.http.{Request, Response}

/** A PATH PATTERN: `/records/:id/versions` — literal segments and `:name` parameters; `*` at the end takes the rest */
final case class Path(segments: List[String]) {
  def matches(path: String): Option[Map[String, String]] = {
    val actual = path.split("/").toList.filter(_.nonEmpty)
    def go(ps: List[String], gs: List[String], acc: Map[String, String]): Option[Map[String, String]] = (ps, gs) match {
      case (Nil, Nil) => Some(acc)
      case ("*" :: Nil, rest) => Some(acc.updated("*", rest.mkString("/")))
      case (p :: pt, g :: gt) if p.startsWith(":") => go(pt, gt, acc.updated(p.drop(1), okay.http.Query.decode(g)))
      case (p :: pt, g :: gt) if p == g => go(pt, gt, acc)
      case _ => None
    }
    go(segments, actual, Map.empty)
  }
  def render(params: Map[String, String]): String = "/" + segments.map(s => if (s.startsWith(":")) okay.http.Query.encode(params.getOrElse(s.drop(1), "")) else s).mkString("/")
}
object Path { def apply(pattern: String): Path = new Path(pattern.split("/").toList.filter(_.nonEmpty)) }

/** A LINK, an affordance: where, how, with what — HATEOAS's link, a form's description for a client that fills it */
final case class Link(rel: String, href: String, method: String = "GET", title: Option[String] = None, schema: Option[Json] = None) {
  def json: Json = Json.Obj(Vector("rel" -> Json.Str(rel), "href" -> Json.Str(href), "method" -> Json.Str(method)) ++ title.map(t => "title" -> Json.Str(t)) ++ schema.map("schema" -> _))
}
/** a representation with its affordances */
final case class Resource(data: Json, links: List[Link]) {
  def json: Json = Json.obj("data" -> data, "links" -> Json.Arr(links.map(_.json).toVector))
  def response(status: Int = 200): Response = Response.json(json, status)
}
/** what went wrong, as data (RFC 9457's shape): a status, a title, the problems by path */
final case class Trouble(status: Int, title: String, problems: Problems = Nil) {
  def json: Json = Json.Obj(Vector("status" -> Json.num(status), "title" -> Json.Str(title)) ++
    (if (problems.isEmpty) Vector.empty else Vector("problems" -> Json.Arr(problems.map(p => Json.obj("path" -> Json.Arr(p.path.map(Json.Str(_)).toVector), "message" -> Json.Str(p.message))).toVector))))
  def response: Response = Response(status, Map("Content-Type" -> "application/problem+json"), Json.print(json))
}

/** routes: a method and a path pattern to a program of the row; dispatched in order, 404 and 405 as data */
class Rests[F <: Effects](implicit implementation: F) {
  val fx: F = implementation
  import fx._

  final case class Route[R[_]](method: String, path: Path, handle: (Map[String, String], Request) => Response ! R)
  def route[R[_]](method: String, pattern: String)(handle: (Map[String, String], Request) => Response ! R): Route[R] = Route(method, Path(pattern), handle)
  def get[R[_]](pattern: String)(handle: (Map[String, String], Request) => Response ! R): Route[R] = route("GET", pattern)(handle)
  def post[R[_]](pattern: String)(handle: (Map[String, String], Request) => Response ! R): Route[R] = route("POST", pattern)(handle)

  def dispatch[R[_]](routes: List[Route[R]])(req: Request): Response ! R = {
    val byPath = routes.flatMap(r => r.path.matches(req.path).map(ps => (r, ps)))
    byPath.find(_._1.method == req.method) match {
      case Some((r, ps)) => r.handle(ps, req)
      case None if byPath.nonEmpty => pure[Response, R](Response.methodNotAllowed(byPath.map(_._1.method).distinct))
      case None => pure[Response, R](Trouble(404, s"no ${req.path}").response)
    }
  }

  /** the request's JSON body read by a shape: the value, or a 400 with the problems by path */
  def body[P](req: Request, shape: Shape[P]): Either[Response, P] = req.json match {
    case Left(e) => Left(Trouble(400, "the body is not JSON", List(Problem(Nil, e))).response)
    case Right(j) => shape.decode(j).left.map(ps => Trouble(400, "the body does not fit", ps).response)
  }
}

object Rests {
  def apply()(implicit fx: Effects): Rests[fx.type] = {
    implicit val exact: fx.type = fx
    new Rests[fx.type]
  }
}
