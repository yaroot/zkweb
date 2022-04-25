package zkweb

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import io.netty.handler.codec.http.QueryStringDecoder
import org.apache.curator.retry.RetryNTimes
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}

import java.net.{InetSocketAddress, URI}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

object Main extends App with EnvUtil {
  val logger = org.log4s.getLogger

  val port: Int      = getEnvInt("HTTP_PORT", 8080)
  val host: String   = getEnv("HTTP_HOST", "127.0.0.1")
  val zkConn: String = getEnv("ZOOKEEPER", "127.0.0.1:2181")

  logger.info(s"Staring on ${host}:${port}")
  logger.info(s"Connecting to ${zkConn}")
  val zk = ZK.create(zkConn)
  zk.start()

  val handlers = new Handlers(zk)

  val server = HttpServer.create()
  server.createContext("/", handlers.indexHandler)
  server.createContext("/browse/", handlers.browseHandler)

  server.bind(new InetSocketAddress(host, port), 16)
  server.setExecutor(Executors.newFixedThreadPool(16))
  server.start()
}

class Handlers(val zk: ZK) {
  def indexHandler: HttpHandler = Handler.mk { ctx =>
    ctx.html(
      200,
      """
         |<html>
         |<ul>
         |  <li><a href=/browse/>browse</a></li>
         |  <li><a href=/tree/>tree</a></li>
         |</ul>
         |""".stripMargin
    )
  }

  def browseHandler: HttpHandler = Handler.mk { ctx =>
    val path = {
      val p = ctx.path.stripPrefix("/browse")
      if (p.length > 1) {
        p.stripSuffix("/")
      } else {
        p
      }
    }

    val node = zk.list(path)

    val body = Vector.newBuilder[String]
    body += "<html>"
    body += s"<h2>${node.path}</h2>"

    body += "<pre>"
    body += """<a href="../">..</a>"""
    node.children.foreach { p =>
      body +=
        s"""<a href="$p/">$p</a>""".stripMargin
    }
    body += "</pre>"

    body += "<h2>data</h2>"
    body += "<pre>"

    if (node.data.length == 0) {
      body += "<h4>empty</h4>"
    } else {
      val str = ZK.tryShowData(node.data)
      if (str.nonEmpty) {
        body += "<h4>text</h4>"
        body += "<code>"
        body += str.getOrElse("")
        body += "</code>"
      } else {
        body += "<h4>base64</h4>"
        body += "<code>"
        body += ZK.b64Encode(node.data)
        body += "</code>"
      }
    }

    body += "</pre>"

    ctx.html(200, body.result().mkString("\n"))
  }
}

trait EnvUtil {
  def getEnv(name: String, default: String): String = {
    env(name).getOrElse(default)
  }

  def getEnvInt(name: String, default: Int): Int = {
    env(name).map(_.toInt).getOrElse(default)
  }

  def env(name: String): Option[String] = Option(System.getenv(name)).filter(_.nonEmpty)
}

case class ZKNode(path: String, children: Vector[String], data: Array[Byte])

trait ZK {
  def list(path: String): ZKNode
  def create(path: String, data: Array[Byte]): String
  def start(): Unit
  def close(): Unit
}

object ZK {
  def tryShowData(data: Array[Byte]): Option[String] = {
    scala.util.Try(new String(data, StandardCharsets.UTF_8)).toOption
  }

  def b64Encode(data: Array[Byte]): String = {
    java.util.Base64.getEncoder.encodeToString(data)
  }

  def create(conn: String): ZK = {
    val client = newCurator(conn)

    new ZK {
      def start(): Unit = client.start()
      def close(): Unit = client.close()

      def list(path: String): ZKNode = {
        val children = client.getChildren.forPath(path).asScala.toVector
        val data     = client.getData.forPath(path)
        ZKNode(path, children, data)
      }

      def create(path: String, data: Array[Byte]): String = {
        client.create().forPath(path, data)
      }
    }
  }

  def newCurator(conn: String): CuratorFramework = {
    CuratorFrameworkFactory.newClient(
      conn,
      60 * 1000,
      1000,
      new RetryNTimes(2, 500)
    )
  }
}

case class HandlerAdapter(handler: Handler) extends HttpHandler {
  val logger = org.log4s.getLogger

  override def handle(exchange: HttpExchange): Unit = {
    logger.info(s"OnRequest: ${exchange.getRequestURI.toString}")
    try {
      val ctx = new HttpCtx {
        val ctx: HttpExchange                    = exchange
        val uri: URI                             = exchange.getRequestURI
        val method: String                       = exchange.getRequestMethod
        val path: String                         = exchange.getRequestURI.getPath
        val query: Map[String, Vector[String]]   =
          new QueryStringDecoder(exchange.getRequestURI.toString, false)
            .parameters()
            .asScala
            .toMap
            .view
            .mapValues(_.asScala.toVector)
            .toMap
            .filter(_._2.nonEmpty)
        val headers: Map[String, Vector[String]] =
          exchange.getRequestHeaders.asScala.toMap.view
            .mapValues(_.asScala.toVector)
            .toMap
            .filter(_._2.nonEmpty)

        def readBody: Array[Byte] = exchange.getRequestBody.readAllBytes()
      }
      handler.handle(ctx)
    } catch {
      case NonFatal(ex) =>
        ex.printStackTrace()
        val error = "500 SERVER ERROR".getBytes()
        exchange.sendResponseHeaders(500, error.length.toLong)
        exchange.getResponseBody.write(error)
        exchange.getResponseBody.close()
    } finally {
      exchange.close()
    }
  }
}

trait Handler {
  def handle(ctx: HttpCtx): Unit
}

object Handler {
  def mk(f: HttpCtx => Unit): HttpHandler =
    HandlerAdapter(new Handler {
      override def handle(ctx: HttpCtx): Unit = f(ctx)
    })
}

trait HttpCtx {
  def ctx: HttpExchange
  def uri: java.net.URI
  def query: Map[String, Vector[String]]
  def headers: Map[String, Vector[String]]
  def method: String
  def path: String
  def readBody: Array[Byte]

  def getQueryParam(name: String): Option[String] = query.get(name).flatMap(_.headOption)
  def getHeader(name: String): Option[String]     = headers.get(name).flatMap(_.headOption)

  def error(code: Int, message: String): Unit = {
    this.text(code, message, "text/plain; charset=utf-8")
  }

  def html(code: Int, body: String): Unit = {
    this.text(code, body, "text/html; charset=utf-8")
  }

  def redirect(code: Int, url: String): Unit = {
    ctx.getResponseHeaders.set("Location", url)
    ctx.sendResponseHeaders(code, 0)
    ctx.getResponseBody.close()
  }

  def text(code: Int, body: String, contentType: String): Unit = {
    val content = body.getBytes(StandardCharsets.UTF_8)
    ctx.getResponseHeaders.set("content-type", contentType)
    ctx.sendResponseHeaders(code, content.length.toLong)
    val writer  = ctx.getResponseBody
    try {
      writer.write(content)
    } finally {
      writer.close()
    }
  }
}
