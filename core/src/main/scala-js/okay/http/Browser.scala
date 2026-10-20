package okay.http

import org.scalajs.dom
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.JSConverters._

/** THE BROWSER'S CLIENT: a `Request` sent by `fetch`, the `Response` back — the transport a face in the page talks to
 * its server through. `origin` empty is the page's own */
object Browser {
  /** the handler of `Fetch` by the browser's `fetch` */
  def fetches[F <: okay.effects.Runner](asynchrony: okay.effects.async.Asynchrony[F], origin: String = "")(implicit ec: ExecutionContext): Fetches[F] =
    new Fetches[F](asynchrony, fetch(origin))
  def fetch(origin: String = "")(implicit ec: ExecutionContext): Request => Future[Response] = req => {
    val url = origin + req.path + (if (req.query.isEmpty) "" else "?" + Query.print(req.query))
    val init = new dom.RequestInit {}
    init.method = req.method.asInstanceOf[dom.HttpMethod]
    init.headers = req.headers.map { case (k, vs) => k -> vs.mkString(", ") }.toJSDictionary.asInstanceOf[dom.HeadersInit]
    if (req.body.nonEmpty) init.body = req.body
    init.redirect = dom.RequestRedirect.follow
    dom.fetch(url, init).toFuture.flatMap { r =>
      r.text().toFuture.map { body =>
        val headers = List("content-type", "location").flatMap(h => Option(r.headers.get(h)).filter(_ != null).map(v => h -> v)).toMap
        Response(r.status, headers, body)
      }
    }
  }
}

/** THE BROWSER'S HANDLER OF `Fetch`: each call sent — by `fetch` (`Browser.fetches`), or by any `send` (a test's server
 * in the same page) — its response awaited through `Async`, so a program performs `Fetch` in a page and the rest of it
 * runs when the response comes, as a browser needs */
class Fetches[F <: okay.effects.Runner](val asynchrony: okay.effects.async.Asynchrony[F], send: Request => Future[Response])(implicit ec: ExecutionContext) {
  import asynchrony.fx._
  def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Fetch, R, Rest], a: In[okay.effects.async.Async, Rest]): A ! Rest =
    handle[Fetch, R, Rest, A, A](p, new Handler[Fetch, Rest, A, A] {
      def ret(x: A) = pure(x)
      def op[X](op: Fetch[X], k: X => A ! Rest) = op match {
        case Fetch.Call(m, url, hs, body, answer) =>
          asynchrony.fromFuture[Response, Rest](send(Request(m, url).copy(headers = hs.map { case (h, v) => h.toLowerCase -> List(v) }, body = body))).flatMap(r => k(answer(r)))
      }
    })
}
