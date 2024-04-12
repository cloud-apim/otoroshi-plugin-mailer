package otoroshi_plugins.com.cloud.apim.otoroshi.plugins.mailer

import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy, QueueOfferResult}
import akka.util.ByteString
import com.google.common.base.Charsets
import com.sun.mail.smtp.SMTPTransport
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.events.AlertEvent
import otoroshi.models.ApiKey
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Results

import java.util.{Properties, UUID}
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.activation.DataHandler
import javax.mail.internet.{InternetAddress, MimeBodyPart, MimeMessage, MimeMultipart}
import javax.mail.util.ByteArrayDataSource
import javax.mail.{Message, Session}
import scala.concurrent.{ExecutionContext, Future}
import scala.util._

case class MailerApiConfiguration(
  host: String,
  port: Option[Int] = None,
  user: Option[String] = None,
  password: Option[String] = None,
  auth: Boolean = false,
  starttlsEnabled: Boolean = false,
  smtps: Boolean = true,
  maxRetries: Int = 5,
) extends NgPluginConfig {
  def json: JsValue = MailerApiConfiguration.format.writes(this)
}

object MailerApiConfiguration {
  val default = MailerApiConfiguration("")
  val format = new Format[MailerApiConfiguration] {
    override def writes(o: MailerApiConfiguration): JsValue = Json.obj(
      "host" -> o.host,
      "port" -> o.port,
      "user" -> o.user,
      "password" -> o.password,
      "auth" -> o.auth,
      "starttls_enabled" -> o.starttlsEnabled,
      "smtps" -> o.smtps,
      "max_retries" -> o.maxRetries,
    )
    override def reads(json: JsValue): JsResult[MailerApiConfiguration] = Try {
      MailerApiConfiguration(
        host = json.select("host").asString,
        port = json.select("port").asOpt[Int],
        user = json.select("user").asOpt[String],
        password = json.select("password").asOpt[String],
        auth = json.select("auth").asOpt[Boolean].getOrElse(false),
        starttlsEnabled = json.select("starttls_enabled").asOpt[Boolean].getOrElse(false),
        smtps = json.select("smtps").asOpt[Boolean].getOrElse(true),
        maxRetries = json.select("max_retries").asOpt[Int].getOrElse(5),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
  }
  val configFlow = Seq(
    "host",
    "port",
    "user",
    "password",
    "auth",
    "starttls_enabled",
    "smtps",
    "max_retries",
  )
  val configSchema = Some(Json.obj(
    "host" -> Json.obj("type" -> "string", "label" -> "Host"),
    "port" -> Json.obj("type" -> "number", "label" -> "port"),
    "user" -> Json.obj("type" -> "string", "label" -> "Username"),
    "password" -> Json.obj("type" -> "password", "label" -> "Password"),
    "auth" -> Json.obj("type" -> "bool", "label" -> "Authentication"),
    "starttls_enabled" -> Json.obj("type" -> "bool", "label" -> "StartTLS"),
    "smtps" -> Json.obj("type" -> "bool", "label" -> "TLS"),
    "max_retries" -> Json.obj("type" -> "number", "label" -> "Max retries"),
  ))
}

case class MailAttachment(name: String, content: ByteString, mimetype: String, disposition: String)

// TODO: support attachments
case class Mail(
   subject: String,
   from: String,
   to: Seq[String],
   cc: Seq[String],
   bcc: Seq[String],
   content: ByteString,
   mimetype: String,
   config: MailerApiConfiguration,
   tryCount: Int,
   attachments: Seq[MailAttachment]
) {
  def json: JsValue = Json.obj(
    "subject" -> subject,
    "from" -> from,
    "to" -> to,
    "cc" -> cc,
    "bcc" -> bcc,
    "content" -> content.utf8String,
    "mimetype" -> mimetype,
    "config" -> (config.json.asObject - "password" ++ Json.obj("password" -> "****")),
    "try_count" -> tryCount,
    "attachments" -> JsArray(attachments.map(a => Json.obj(
      "name" -> a.name,
      "content" -> a.content.encodeBase64.utf8String,
      "mimetype" -> a.mimetype,
      "disposition" -> a.disposition
    ))),
  )
}

class MailerEndpoint extends NgBackendCall {

  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Mailing"), NgPluginCategory.Custom("Cloud APIM"))
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean = true
  override def core: Boolean = true
  override def name: String = "Mailer endpoint"
  override def description: Option[String] = "This plugin provide an endpoint to send email using SMTP".some
  override def defaultConfigObject: Option[NgPluginConfig] = Some(MailerApiConfiguration.default)
  override def useDelegates: Boolean = false
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = MailerApiConfiguration.configFlow
  override def configSchema: Option[JsObject] = MailerApiConfiguration.configSchema

  private val sendingEc = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(Runtime.getRuntime.availableProcessors() + 1))
  private val queueRef = new AtomicReference[SourceQueueWithComplete[Mail]]()

  override def start(env: Env): Future[Unit] = {
    env.logger.info("[Cloud APIM] the 'Mailer endpoint' plugin is available !")
    ().vfuture
  }

  private def getQueueRef()(implicit env: Env): SourceQueueWithComplete[Mail] = queueRef.synchronized {
    if (queueRef.get() == null) {
      val stream = Source.queue[Mail](128, OverflowStrategy.dropTail).mapAsync(Runtime.getRuntime.availableProcessors() + 1) { mail =>
        doSendEmail(mail)(env)
      }
      val (queue, done) = stream.toMat(Sink.ignore)(Keep.both).run()(env.analyticsMaterializer)
      queueRef.set(queue)
    }
    queueRef.get()
  }

  private def doSendEmail(mail: Mail)(implicit env: Env): Future[Either[Throwable, Unit]] = {
    val config = mail.config
    val props    = new Properties()
    val protocol = if (config.smtps) "smtps" else "smtp"
    props.put(s"mail.${protocol}.host", config.host)
    config.port.map(port => props.put(s"mail.${protocol}.port", port))
    props.put(s"mail.${protocol}.starttls.enable", config.starttlsEnabled)
    props.put(s"mail.${protocol}.auth", config.auth)

    val session = Session.getInstance(props, null)
    val msg     = new MimeMessage(session)
    msg.setFrom(new InternetAddress(mail.from))
    mail.to.foreach(to => msg.addRecipient(Message.RecipientType.TO, new InternetAddress(to)))
    mail.cc.foreach(to => msg.addRecipient(Message.RecipientType.CC, new InternetAddress(to)))
    mail.bcc.foreach(to => msg.addRecipient(Message.RecipientType.BCC, new InternetAddress(to)))
    msg.setSubject(mail.subject)
    if (mail.attachments.isEmpty) {
      msg.setContent(mail.content.utf8String, mail.mimetype)
    } else {
      val messageBodyPart = new MimeBodyPart()
      messageBodyPart.setContent(mail.content.utf8String, mail.mimetype)
      val attachments = mail.attachments.map { att =>
        val attachmentPart = new MimeBodyPart()
        attachmentPart.setFileName(att.name)
        if (att.disposition == "inline") {
          attachmentPart.setContentID(s"<${att.name}>")
          attachmentPart.setDisposition(javax.mail.Part.INLINE)
        }
        attachmentPart.setDataHandler(new DataHandler(new ByteArrayDataSource(att.content.toArray, att.mimetype)))
        attachmentPart
      }
      val multipart = new MimeMultipart("related")
      multipart.addBodyPart(messageBodyPart)
      attachments.foreach(att => multipart.addBodyPart(att))
      msg.setContent(multipart)
    }
    val addresses = mail.to ++ mail.cc ++ mail.bcc

    Future {
      Using(session.getTransport(protocol).asInstanceOf[SMTPTransport]) { transport =>
        transport.connect(config.host, config.user.getOrElse(mail.from), config.password.orNull)
        transport.sendMessage(msg, msg.getAllRecipients)
      }
      .toEither
      .left.map { t =>
        if (mail.tryCount < config.maxRetries) {
          env.logger.error(s"error while trying to send email to ${addresses.mkString(", ")}, retrying ...", t)
          getQueueRef().offer(mail.copy(tryCount = mail.tryCount + 1))
        } else {
          env.logger.error(s"error while trying to send email to ${addresses.mkString(", ")}, publishing alert ...", t)
          EmailSendingError(UUID.randomUUID().toString, mail).toAnalytics()
        }
        t
      }
      .right.map { r =>
        env.logger.info(s"email sent to ${addresses.mkString(", ")}")
        EmailSendingSuccess(UUID.randomUUID().toString, mail).toAnalytics()
        r
      }
    }(sendingEc)
  }

  private def doQueueEmail(ctx: NgbBackendCallContext, value: JsValue, config: MailerApiConfiguration)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    getQueueRef().offer(Mail(
      subject = value.select("subject").asOpt[String].getOrElse("Subject"),
      from = value.select("from").asString,
      to = value.select("to").asOpt[Seq[String]].getOrElse(Seq.empty),
      cc = value.select("cc").asOpt[Seq[String]].getOrElse(Seq.empty),
      bcc = value.select("bcc").asOpt[Seq[String]].getOrElse(Seq.empty),
      mimetype = value.select("mimetype").asOpt[String] match {
        case Some(mimetype) => mimetype
        case None if value.select("html").isDefined => "text/html; charset=utf-8"
        case None if value.select("text").isDefined => "text/plain; charset=utf-8"
        case _ => "text/html; charset=utf-8"
      },
      content = value.select("body").asOpt[String].orElse(value.select("html").asOpt[String]).orElse(value.select("text").asOpt[String]).getOrElse("").byteString,
      config = config,
      tryCount = 0,
      attachments = value.select("attachments").asOpt[Seq[JsObject]] match {
        case None => Seq.empty
        case Some(seq) => seq.map(obj => MailAttachment(
          obj.select("name").asString,
          obj.select("content").asString.byteString.decodeBase64,
          obj.select("mimetype").asOpt[String].getOrElse("application/octet-stream"),
          obj.select("disposition").asOpt[String].getOrElse("attachment"),
        ))
      }
    )).flatMap {
      case QueueOfferResult.QueueClosed => BackendCallResponse(NgPluginHttpResponse.fromResult(Results.InternalServerError(Json.obj("queued" -> false, "error" -> "queue already closed"))), None).rightf
      case QueueOfferResult.Dropped => BackendCallResponse(NgPluginHttpResponse.fromResult(Results.InternalServerError(Json.obj("queued" -> false, "error" -> "email dropped"))), None).rightf
      case QueueOfferResult.Failure(e) => BackendCallResponse(NgPluginHttpResponse.fromResult(Results.InternalServerError(Json.obj("queued" -> false, "error" -> e.getMessage))), None).rightf
      case QueueOfferResult.Enqueued => BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(Json.obj("queued" -> true))), None).rightf
    }.recover {
      case t => BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(Json.obj("enqueue" -> false, "error" -> t.getMessage))), None).right
    }
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(MailerApiConfiguration.format).getOrElse(MailerApiConfiguration.default)
    if (ctx.request.method == "POST" && ctx.request.hasBody && ctx.request.contentType.contains("application/json")) {
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        doQueueEmail(ctx, Json.parse(bodyRaw.utf8String), config)
      }
    } else {
      BackendCallResponse(NgPluginHttpResponse.fromResult(Results.NotFound(Json.obj("error" -> "not found"))), None).rightf
    }
  }
}

case class EmailSendingError(
   `@id`: String,
   mail: Mail,
   `@timestamp`: DateTime = DateTime.now()
) extends AlertEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  override def toJson(implicit _env: Env): JsValue =
    Json.obj(
      "@id"           -> `@id`,
      "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
      "@type"         -> `@type`,
      "@product"      -> _env.eventsName,
      "@serviceId"    -> `@serviceId`,
      "@service"      -> `@service`,
      "@env"          -> "prod",
      "alert"         -> "EmailSendingError",
      "mail" -> mail.json,
    )
}


case class EmailSendingSuccess(
                              `@id`: String,
                              mail: Mail,
                              `@timestamp`: DateTime = DateTime.now()
                            ) extends AlertEvent {

  override def `@service`: String   = "Otoroshi"
  override def `@serviceId`: String = "--"

  override def fromOrigin: Option[String]    = None
  override def fromUserAgent: Option[String] = None

  override def toJson(implicit _env: Env): JsValue =
    Json.obj(
      "@id"           -> `@id`,
      "@timestamp"    -> play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(`@timestamp`),
      "@type"         -> `@type`,
      "@product"      -> _env.eventsName,
      "@serviceId"    -> `@serviceId`,
      "@service"      -> `@service`,
      "@env"          -> "prod",
      "alert"         -> "EmailSendingSuccess",
      "mail" -> mail.json,
    )
}