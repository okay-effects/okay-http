package okay.http

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetSocketAddress, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.{CountDownLatch, LinkedBlockingQueue}
import okay.effects.Effects
import scala.jdk.CollectionConverters._

/** THE JDK'S SERVER as a handler of `Http`, no dependency: requests queued by the server's threads, taken by the program's
 * `Receive`, each answered when the program `Respond`s; `stop()` ends the loop with a `None`. The program's loop runs in
 * the thread that runs it; the server's own threads only queue */
final class JdkServer private (val server: HttpServer, val port: Int) {
  private final case class Pending(request: Request, var response: Response, done: CountDownLatch)
  private val queue = new LinkedBlockingQueue[Option[Pending]]
  server.createContext("/", (ex: HttpExchange) => {
    val (body, binary) = Bytes.body(ex.getRequestBody.readAllBytes())
    val uri = ex.getRequestURI
    val headers = ex.getRequestHeaders.asScala.map { case (k, vs) => k.toLowerCase -> vs.asScala.toList }.toMap
    val pending = Pending(Request(ex.getRequestMethod, uri.getRawPath, Query.parse(Option(uri.getRawQuery).getOrElse("")), headers, body, binary), Response(500), new CountDownLatch(1))
    queue.put(Some(pending))
    pending.done.await()
    val r = pending.response
    r.headers.foreach { case (k, v) => ex.getResponseHeaders.add(k, v) }
    val bytes = r.content.toArray
    ex.sendResponseHeaders(r.status, if (bytes.isEmpty) -1 else bytes.length)
    if (bytes.nonEmpty) ex.getResponseBody.write(bytes)
    ex.close()
  })
  server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
  server.start()
  private var current: Option[Pending] = None
  /** the next request, blocking; `None` once stopped */
  def receive(): Option[Request] = queue.take() match {
    case Some(p) => current = Some(p); Some(p.request)
    case None => None
  }
  def respond(r: Response): Unit = current.foreach { p => p.response = r; p.done.countDown(); current = None }
  def stop(): Unit = { queue.put(None); server.stop(0) }
}
object JdkServer {
  /** on `port`, 0 for any free one */
  def start(port: Int = 0, host: String = "127.0.0.1"): JdkServer = {
    val s = HttpServer.create(new InetSocketAddress(host, port), 0)
    new JdkServer(s, s.getAddress.getPort)
  }
}

/** the JDK's handlers, over any implementation */
class JdkHttp[F <: Effects with Singleton](implicit implementation: F) {
  val fx: F = implementation
  import fx._

  /** A SERVICE SERVED, whole: `run(new JdkHttp().serve(port, host)(service))` — the server started when the program
   * runs, every request answered by the service, until the server stops; a line on standard error says where */
  def serve(port: Int = 8080, host: String = "127.0.0.1", quiet: Boolean = false)(service: Request => Response ! ({ type L[X] = okay.effects.Sum[Pure, Http, X] })#L): Unit ! Pure = {
    type H[X] = okay.effects.Sum[Pure, Http, X]
    pure(()).flatMap { _ =>
      val server = JdkServer.start(port, host)
      if (!quiet) System.err.println(s"serving at http://$host:${server.port}/")
      jdk(server)(new Https[fx.type]()(fx).serve[H](service))
    }
  }
  /** `Http` answered by a running `JdkServer`: in place, blocking on the queue */
  def jdk(server: JdkServer): Jdk = new Jdk(server)
  final class Jdk(server: JdkServer) {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Http, R, Rest]): A ! Rest =
      handle[Http, R, Rest, A, A](p, new TailHandler[Http, Rest, A, A] {
        def ret(a: A) = pure(a)
        def answer[X](op: Http[X]): X = op match {
          case Http.Receive(answer) => answer(server.receive())
          case Http.Respond(response, answer) => server.respond(response); answer(())
        }
      })
  }
  /** `Fetch` by the JDK's `HttpClient`, in place, blocking */
  def jdkClient(client: HttpClient = HttpClient.newHttpClient()): JdkClient = new JdkClient(client)
  final class JdkClient(client: HttpClient) {
    /** headers the JDK sets itself, and refuses */
    private val restricted = Set("connection", "content-length", "expect", "host", "upgrade")
    private def call(url: String, req: Request): Response = {
      val content = req.content.toArray
      val b = HttpRequest.newBuilder(URI.create(url)).method(req.method,
        if (content.isEmpty) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(content))
      req.headers.foreach { case (k, vs) => if (!restricted(k.toLowerCase)) vs.foreach(v => b.header(k, v)) }
      val r = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
      Response(r.statusCode(), r.headers().map().asScala.map { case (k, vs) => k -> vs.asScala.mkString(", ") }.toMap)
        .withContent(Bytes(r.body()))
    }
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Fetch, R, Rest]): A ! Rest =
      handle[Fetch, R, Rest, A, A](p, new TailHandler[Fetch, Rest, A, A] {
        def ret(a: A) = pure(a)
        def answer[X](op: Fetch[X]): X = op match {
          case Fetch.Call(method, url, headers, body, answer) => answer(call(url, Fetch.request(method, url, headers, body)))
          case Fetch.Send(base, r, answer) => answer(call(Fetch.target(base, r), r))
        }
      })
  }
}
