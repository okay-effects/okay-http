package okay.http

import okay.effects.Effects

/** THE SERVER'S EFFECT: a request received (`None`: the server stopped), a response given. Its handlers: the JDK's
 * server on a port (`jdk`, the JVM), a script of requests for a test (`scripted`), a loopback a client in the same
 * process calls (`Fetches.loopback`) */
sealed trait Http[X]
object Http {
  final case class Receive[X](answer: Option[Request] => X) extends Http[X]
  final case class Respond[X](response: Response, answer: Unit => X) extends Http[X]
}

/** THE CLIENT'S EFFECT: a request to a URL, its response */
sealed trait Fetch[X]
object Fetch {
  final case class Call[X](method: String, url: String, headers: Map[String, String], body: String, answer: Response => X) extends Fetch[X]
}

/** `F` the implementation's singleton type: with an implicit implementation in scope (`import okay.effects.machine.implicits._`)
 * `new Https()` infers it, on both compilers; the `Singleton` bound keeps it exact, so its `!` is the one everything else uses */
class Https[F <: Effects with Singleton](implicit implementation: F) {
  val fx: F = implementation
  import fx._

  def receive[R[_]](implicit in: In[Http, R]): Option[Request] ! R = perform[Http].apply[R, Option[Request]](Http.Receive(r => r))
  def respond[R[_]](response: Response)(implicit in: In[Http, R]): Unit ! R = perform[Http].apply[R, Unit](Http.Respond(response, u => u))

  /** the loop: every request answered by `handle`, a program of the row, until the server stops */
  def serve[R[_]](handle: Request => Response ! R)(implicit in: In[Http, R]): Unit ! R = {
    def loop: Unit ! R = receive[R].flatMap {
      case None => pure[Unit, R](())
      case Some(req) => handle(req).flatMap(respond[R]).flatMap(_ => loop)
    }
    loop
  }

  /** the requests of a script, in order; the responses collected */
  def scripted(requests: List[Request]): Scripted = new Scripted(requests)
  final class Scripted(requests: List[Request]) {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Http, R, Rest]): (List[Response], A) ! Rest =
      handle[Http, (List[Request], List[Response]), R, Rest, A, (List[Response], A)](p, (requests, Nil),
        new StatefulTailHandler[Http, (List[Request], List[Response]), Rest, A, (List[Response], A)] {
          def ret(s: (List[Request], List[Response]), a: A) = pure((s._2.reverse, a))
          def answer[X](cell: Cell[(List[Request], List[Response])], op: Http[X]): X = op match {
            case Http.Receive(answer) => cell.get._1 match {
              case r :: rest => cell.set((rest, cell.get._2)); answer(Some(r))
              case Nil => answer(None)
            }
            case Http.Respond(response, answer) => cell.set((cell.get._1, response :: cell.get._2)); answer(())
          }
        })
  }

  // ---- the client
  def fetch[R[_]](method: String, url: String, headers: Map[String, String] = Map.empty, body: String = "")(implicit in: In[Fetch, R]): Response ! R =
    perform[Fetch].apply[R, Response](Fetch.Call(method, url, headers, body, r => r))
  def get[R[_]](url: String)(implicit in: In[Fetch, R]): Response ! R = fetch[R]("GET", url)
  def postJson[R[_]](url: String, j: okay.effects.json.Json)(implicit in: In[Fetch, R]): Response ! R =
    fetch[R]("POST", url, Map("Content-Type" -> "application/json"), okay.effects.json.Json.print(j))

  /** a client's calls answered by a table, by method and URL — a test's */
  def table(answers: PartialFunction[(String, String), Response]): Table = new Table(answers)
  final class Table(answers: PartialFunction[(String, String), Response]) {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Fetch, R, Rest]): A ! Rest =
      handle[Fetch, R, Rest, A, A](p, new TailHandler[Fetch, Rest, A, A] {
        def ret(a: A) = pure(a)
        def answer[X](op: Fetch[X]): X = op match {
          case Fetch.Call(m, url, _, _, answer) => answer(answers.applyOrElse((m, url), (_: (String, String)) => Response.notFound(s"$m $url")))
        }
      })
  }
  /** a client's calls given to a server program in the same process: the URL's path and query as the request, the
   * server's response back — a program of the row outside */
  def loopback[Rest[_]](server: Request => Response ! Rest): Loopback[Rest] = new Loopback[Rest](server)
  final class Loopback[Rest[_]](server: Request => Response ! Rest) {
    def apply[R[_], A](p: A ! R)(implicit sp: Split[Fetch, R, Rest]): A ! Rest =
      handle[Fetch, R, Rest, A, A](p, new Handler[Fetch, Rest, A, A] {
        def ret(a: A) = pure(a)
        def op[X](op: Fetch[X], k: X => A ! Rest) = op match {
          case Fetch.Call(m, url, headers, body, answer) =>
            val target = url.indexOf("://") match { case -1 => url; case i => val rest = url.substring(i + 3); rest.indexOf('/') match { case -1 => "/"; case j => rest.substring(j) } }
            server(Request(m, target).copy(headers = headers.map { case (k, v) => k.toLowerCase -> List(v) }, body = body)).flatMap(r => k(answer(r)))
        }
      })
  }
}

object Https {
  def apply()(implicit fx: Effects): Https[fx.type] = {
    implicit val exact: fx.type = fx
    new Https[fx.type]
  }
}
