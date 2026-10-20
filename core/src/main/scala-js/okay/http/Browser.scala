package okay.http

import org.scalajs.dom
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.JSConverters._

/** THE BROWSER'S CLIENT: a `Request` sent by `fetch`, the `Response` back — the transport a face in the page talks to
 * its server through. `origin` empty is the page's own */
object Browser {
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
