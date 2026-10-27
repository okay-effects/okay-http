package okay.http

import okay.effects.json.Json

/** A REQUEST as data: the method, the path, the query and the headers (names lowercased) as lists, the body as text —
 * or, when it is not text (an upload, an image), as `bytes`: then `bytes` is the body and `body` is empty */
final case class Request(method: String, path: String, query: Map[String, List[String]] = Map.empty, headers: Map[String, List[String]] = Map.empty, body: String = "",
                         bytes: Option[Bytes] = None) {
  def header(name: String): Option[String] = headers.get(name.toLowerCase).flatMap(_.headOption)
  def param(name: String): Option[String] = query.get(name).flatMap(_.headOption)
  /** the body as bytes, text or not */
  def content: Bytes = bytes.getOrElse(Bytes.of(body))
  /** the body these bytes: kept as text if they are UTF-8, as bytes if not */
  def withContent(raw: Bytes): Request = {
    val (text, binary) = Bytes.body(raw.toArray)
    copy(body = text, bytes = binary)
  }
  def json: Either[String, Json] = okay.effects.json.Descent.parse(body).left.map(_.message)
}
object Request {
  /** `GET /a/b?x=1&y=2`: the path and the query split */
  def apply(method: String, target: String): Request = {
    val (path, q) = target.indexOf('?') match { case -1 => (target, ""); case i => (target.substring(0, i), target.substring(i + 1)) }
    new Request(method, path, Query.parse(q))
  }
  def get(target: String): Request = apply("GET", target)
  def post(target: String, body: String, contentType: String = "application/json"): Request =
    apply("POST", target).copy(headers = Map("content-type" -> List(contentType)), body = body)
  def json(method: String, target: String, j: Json): Request = apply(method, target).copy(headers = Map("content-type" -> List("application/json")), body = Json.print(j))
}

object Query {
  def parse(q: String): Map[String, List[String]] =
    if (q.isEmpty) Map.empty
    else q.split("&").toList.filter(_.nonEmpty).map { kv =>
      kv.indexOf('=') match { case -1 => (decode(kv), ""); case i => (decode(kv.substring(0, i)), decode(kv.substring(i + 1))) }
    }.groupBy(_._1).map { case (k, vs) => k -> vs.map(_._2) }
  def print(q: Map[String, List[String]]): String = q.toList.flatMap { case (k, vs) => vs.map(v => encode(k) + "=" + encode(v)) }.mkString("&")
  def decode(s: String): String = {
    val b = new java.lang.StringBuilder; var i = 0
    while (i < s.length) {
      s.charAt(i) match {
        case '+' => b.append(' '); i += 1
        case '%' if i + 2 < s.length + 0 && i + 2 <= s.length - 1 =>
          try { b.append(Integer.parseInt(s.substring(i + 1, i + 3), 16).toChar); i += 3 } catch { case _: NumberFormatException => b.append('%'); i += 1 }
        case c => b.append(c); i += 1
      }
    }
    // the percent-decoded bytes are UTF-8: redo by bytes where any was above ASCII
    val raw = b.toString
    if (raw.exists(_ > 0x7f)) new String(raw.map(_.toByte).toArray, "UTF-8") else raw
  }
  def encode(s: String): String = s.getBytes("UTF-8").flatMap { b =>
    val c = (b & 0xff).toChar
    if (c.isLetterOrDigit && c < 0x80 || "-_.~".contains(c)) c.toString else f"%%${b & 0xff}%02X"
  }.mkString
}

/** A RESPONSE as data: the body as text — or, when it is not text, as `bytes` (then `body` is empty) */
final case class Response(status: Int, headers: Map[String, String] = Map.empty, body: String = "",
                          bytes: Option[Bytes] = None) {
  def header(name: String, value: String): Response = copy(headers = headers.updated(name, value))
  /** the body as bytes, text or not */
  def content: Bytes = bytes.getOrElse(Bytes.of(body))
  /** the body these bytes: kept as text if they are UTF-8, as bytes if not */
  def withContent(raw: Bytes): Response = {
    val (text, binary) = Bytes.body(raw.toArray)
    copy(body = text, bytes = binary)
  }
}
object Response {
  def ok(body: String, contentType: String = "text/plain; charset=utf-8"): Response = Response(200, Map("Content-Type" -> contentType), body)
  def json(j: Json, status: Int = 200): Response = Response(status, Map("Content-Type" -> "application/json"), Json.print(j))
  def html(body: String, status: Int = 200): Response = Response(status, Map("Content-Type" -> "text/html; charset=utf-8"), body)
  def text(body: String, status: Int = 200): Response = Response(status, Map("Content-Type" -> "text/plain; charset=utf-8"), body)
  def redirect(location: String, status: Int = 303): Response = Response(status, Map("Location" -> location))
  def notFound(what: String = "not found"): Response = text(what, 404)
  def badRequest(what: String): Response = text(what, 400)
  def methodNotAllowed(allowed: List[String]): Response = Response(405, Map("Allow" -> allowed.mkString(", "), "Content-Type" -> "text/plain; charset=utf-8"), "method not allowed")
  def noContent: Response = Response(204)
  /** bytes as they are: a file, an image */
  def binary(b: Bytes, contentType: String = "application/octet-stream", status: Int = 200): Response =
    Response(status, Map("Content-Type" -> contentType)).withContent(b)
}
