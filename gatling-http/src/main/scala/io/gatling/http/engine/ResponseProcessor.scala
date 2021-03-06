/*
 * Copyright 2011-2018 GatlingCorp (http://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatling.http.engine

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import io.gatling.commons.stats.{ KO, OK, Status }
import io.gatling.commons.util.ClockSingleton.nowMillis
import io.gatling.commons.util.StringHelper.Eol
import io.gatling.core.check.Check
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.session.Session
import io.gatling.core.stats.StatsEngine
import io.gatling.core.util.NameGen
import io.gatling.http.HeaderNames
import io.gatling.http.action.{ HttpTx, ResourceTx }
import io.gatling.http.check.{ HttpCheck, HttpCheckScope }
import io.gatling.http.client.{ Request, RequestBuilder => AhcRequestBuilder }
import io.gatling.http.client.ahc.uri.Uri
import io.gatling.http.cookie.CookieSupport
import io.gatling.http.fetch.{ CssResourceFetched, RegularResourceFetched }
import io.gatling.http.referer.RefererHandling
import io.gatling.http.response.Response
import io.gatling.http.util._
import io.gatling.http.util.HttpHelper.{ isCss, resolveFromUri }
import io.gatling.netty.util.ahc.StringBuilderPool

import akka.actor.{ ActorRefFactory, Props }
import com.typesafe.scalalogging.StrictLogging
import io.netty.handler.codec.http.HttpMethod._
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpResponseStatus._

object ResponseProcessor extends StrictLogging {

  private val IsTraceEnabled = logger.underlying.isTraceEnabled
}

class ResponseProcessor(statsEngine: StatsEngine, httpEngine: HttpEngine, configuration: GatlingConfiguration)(implicit actorRefFactory: ActorRefFactory) extends StrictLogging with NameGen {

  private def abort(tx: HttpTx, t: Throwable): Unit = {
    logger.error(s"ResponseProcessor crashed on session=${tx.session} request=${tx.request.requestName}: ${tx.request.clientRequest} resourceTx=${tx.resourceTx} redirectCount=${tx.redirectCount}, forwarding user to the next action", t)
    tx.resourceTx match {
      case Some(ResourceTx(fetcher, uri)) => fetcher ! RegularResourceFetched(uri, KO, Session.Identity, tx.silent)
      case _                              => tx.next ! tx.session.markAsFailed
    }
  }

  def onCompleted(tx: HttpTx, response: Response): Unit =
    try {
      processResponse(tx, response)
    } catch {
      case NonFatal(t) => abort(tx, t)
    }

  def onThrowable(tx: HttpTx, response: Response, errorMessage: String): Unit =
    try {
      ko(tx, Session.Identity, response, errorMessage)
    } catch {
      case NonFatal(t) => abort(tx, t)
    }

  private def logRequest(
    tx:           HttpTx,
    status:       Status,
    response:     Response,
    errorMessage: Option[String] = None
  ): Unit =
    if (!tx.silent) {
      val fullRequestName = tx.fullRequestName
      def dump = {
        // hack: pre-cache url because it would reset the StringBuilder
        tx.request.clientRequest.getUri.toUrl
        val buff = StringBuilderPool.DEFAULT.get()
        buff.append(Eol).append(">>>>>>>>>>>>>>>>>>>>>>>>>>").append(Eol)
        buff.append("Request:").append(Eol).append(s"$fullRequestName: $status ${errorMessage.getOrElse("")}").append(Eol)
        buff.append("=========================").append(Eol)
        buff.append("Session:").append(Eol).append(tx.session).append(Eol)
        buff.append("=========================").append(Eol)
        buff.append("HTTP request:").append(Eol).appendRequest(tx.request.clientRequest, response.wireRequestHeaders, configuration.core.charset)
        buff.append("=========================").append(Eol)
        buff.append("HTTP response:").append(Eol).appendResponse(response).append(Eol)
        buff.append("<<<<<<<<<<<<<<<<<<<<<<<<<")
        buff.toString
      }

      if (status == KO) {
        logger.warn(s"Request '$fullRequestName' failed: ${errorMessage.getOrElse("")}")
        if (!ResponseProcessor.IsTraceEnabled) {
          logger.debug(dump)
        }
      }

      logger.trace(dump)

      statsEngine.logResponse(
        tx.session,
        fullRequestName,
        response.startTimestamp,
        response.endTimestamp,
        status,
        response.status.map(httpStatus => Integer.toString(httpStatus.code)),
        errorMessage
      )
    }

  /**
   * This method is used to send a message to the data writer actor and then execute the next action
   *
   * @param tx the HTTP transaction
   * @param update the update to be applied on the Session
   * @param status the status of the request
   * @param response the response
   */
  private def executeNext(tx: HttpTx, update: Session => Session, status: Status, response: Response): Unit =
    tx.resourceTx match {
      case Some(ResourceTx(resourceFetcher, uri)) =>
        if (isCss(response.headers)) {
          val httpProtocol = tx.request.config.httpComponents.httpProtocol
          resourceFetcher ! CssResourceFetched(uri, status, update, tx.silent, response.status, response.lastModifiedOrEtag(httpProtocol), response.body.string)
        } else {
          resourceFetcher ! RegularResourceFetched(uri, status, update, tx.silent)
        }

      case _ =>
        val maybeResourceFetcherActor =
          if (status == KO)
            None
          else
            httpEngine.resourceFetcherActorForFetchedPage(response, tx)

        maybeResourceFetcherActor match {
          case Some(resourceFetcherActor) => actorRefFactory.actorOf(Props(resourceFetcherActor()), genName("resourceFetcher"))
          case None                       => tx.next ! tx.session.increaseDrift(nowMillis - response.endTimestamp)
        }
    }

  private def logAndExecuteNext(tx: HttpTx, update: Session => Session, status: Status, response: Response, message: Option[String]): Unit = {

    val statusUpdate = status match {
      case KO if !tx.silent => Session.MarkAsFailedUpdate
      case _                => Session.Identity
    }
    val groupUpdate = logGroupRequestUpdate(tx, status, response.startTimestamp, response.endTimestamp)
    val totalUpdate = update andThen statusUpdate andThen groupUpdate

    val newTx = tx.copy(session = totalUpdate(tx.session))
    logRequest(newTx, status, response, message)
    // we pass update and not totalUpdate because it's only used for resources where updates are handled differently
    executeNext(newTx, update, status, response)
  }

  private def ko(tx: HttpTx, update: Session => Session, response: Response, message: String): Unit =
    logAndExecuteNext(tx, update, KO, response, Some(message))

  private def logGroupRequestUpdate(tx: HttpTx, status: Status, startTimestamp: Long, endTimestamp: Long): Session => Session =
    if (tx.resourceTx.isEmpty && !tx.silent)
      // resource logging is done in ResourceFetcher
      _.logGroupRequest(startTimestamp, endTimestamp, status)
    else
      Session.Identity

  /**
   * This method processes the response if needed for each checks given by the user
   */
  private def processResponse(tx: HttpTx, response: Response): Unit = {

    import tx.request.config.httpComponents._

    def redirectRequest(status: HttpResponseStatus, redirectUri: Uri, sessionWithUpdatedCookies: Session): Request = {
      val originalRequest = tx.request.clientRequest
      val originalMethod = originalRequest.getMethod

      val switchToGet = originalMethod != GET && (status == HttpResponseStatus.MOVED_PERMANENTLY || status == SEE_OTHER || (status == FOUND && !httpProtocol.responsePart.strict302Handling))
      val keepBody = status == TEMPORARY_REDIRECT || status == PERMANENT_REDIRECT || (status == FOUND && httpProtocol.responsePart.strict302Handling)

      val newHeaders = originalRequest.getHeaders
        .remove(HeaderNames.Host)
        .remove(HeaderNames.ContentLength)
        .remove(HeaderNames.Cookie)

      if (!keepBody)
        newHeaders.remove(HeaderNames.ContentType)

      val requestBuilder = new AhcRequestBuilder(if (switchToGet) GET else originalMethod, redirectUri)
        .setHeaders(newHeaders)
        .setHttp2Enabled(originalRequest.isHttp2Enabled)
        .setLocalAddress(originalRequest.getLocalAddress)
        .setNameResolver(originalRequest.getNameResolver)
        .setProxyServer(originalRequest.getProxyServer)
        .setRealm(originalRequest.getRealm)
        .setRequestTimeout(originalRequest.getRequestTimeout)

      if (originalRequest.getUri.isSameBase(redirectUri)) {
        // we can only assume the virtual host is still valid if the baseUrl is the same
        requestBuilder.setVirtualHost(originalRequest.getVirtualHost)
      }

      if (!httpProtocol.proxyPart.proxyExceptions.contains(redirectUri.getHost)) {
        val originalRequestProxy = if (originalRequest.getUri.getHost == redirectUri.getHost) Option(originalRequest.getProxyServer) else None
        val protocolProxy = httpProtocol.proxyPart.proxy
        originalRequestProxy.orElse(protocolProxy).foreach(requestBuilder.setProxyServer)
      }

      if (keepBody) {
        Option(originalRequest.getBody).foreach(requestBuilder.setBody)
      }

      val cookies = CookieSupport.getStoredCookies(sessionWithUpdatedCookies, redirectUri)
      if (cookies.nonEmpty) {
        requestBuilder.setCookies(cookies.asJava)
      }

      requestBuilder.build(false)
    }

    def redirect(status: HttpResponseStatus, update: Session => Session): Unit =
      if (tx.request.config.maxRedirects == tx.redirectCount) {
        ko(tx, update, response, s"Too many redirects, max is ${tx.request.config.maxRedirects}")

      } else {
        response.header(HeaderNames.Location) match {
          case Some(location) =>
            val redirectURI = resolveFromUri(tx.request.clientRequest.getUri, location)

            val cacheRedirectUpdate =
              if (httpProtocol.requestPart.cache)
                cacheRedirect(tx.request.clientRequest, redirectURI)
              else
                Session.Identity

            val groupUpdate = logGroupRequestUpdate(tx, OK, response.startTimestamp, response.endTimestamp)

            val totalUpdate = update andThen cacheRedirectUpdate andThen groupUpdate
            val newSession = totalUpdate(tx.session)

            val loggedTx = tx.copy(session = newSession)
            logRequest(loggedTx, OK, response)

            val newClientRequest = redirectRequest(status, redirectURI, newSession)
            val redirectTx = loggedTx.copy(
              request = loggedTx.request.copy(clientRequest = newClientRequest),
              redirectCount = tx.redirectCount + 1,
              update = if (tx.resourceTx.isEmpty) Session.Identity else totalUpdate
            )
            HttpTx.start(redirectTx)

          case _ =>
            ko(tx, update, response, "Redirect status, yet no Location header")
        }
      }

    def cacheRedirect(originalRequest: Request, redirectUri: Uri): Session => Session =
      response.status match {
        case Some(code) if HttpHelper.isPermanentRedirect(code) =>
          httpCaches.addRedirect(_, originalRequest, redirectUri)
        case _ => Session.Identity
      }

    def checkAndProceed(sessionUpdate: Session => Session, checks: List[HttpCheck]): Unit = {

      val (checkSaveUpdate, checkError) = Check.check(response, tx.session, checks)

      val status = checkError match {
        case None => OK
        case _    => KO
      }

      val cacheContentUpdate = httpCaches.cacheContent(httpProtocol, tx.request.clientRequest, response)

      val totalUpdate = sessionUpdate andThen cacheContentUpdate andThen checkSaveUpdate

      logAndExecuteNext(tx, totalUpdate, status, response, checkError.map(_.message))
    }

    response.status match {

      case Some(status) =>
        val uri = tx.request.clientRequest.getUri
        val storeCookiesUpdate: Session => Session =
          response.cookies match {
            case Nil     => Session.Identity
            case cookies => CookieSupport.storeCookies(_, uri, cookies)
          }
        val newUpdate = tx.update andThen storeCookiesUpdate

        if (HttpHelper.isRedirect(status) && tx.request.config.followRedirect) {
          redirect(status, newUpdate)

        } else {
          val checks =
            if (HttpHelper.isNotModified(status))
              tx.request.config.checks.filter(c => c.scope != HttpCheckScope.Body && c.scope != HttpCheckScope.Checksum)
            else
              tx.request.config.checks

          val storeRefererUpdate =
            if (tx.resourceTx.isEmpty)
              RefererHandling.storeReferer(tx.request.clientRequest, response, httpProtocol)
            else Session.Identity

          checkAndProceed(newUpdate andThen storeRefererUpdate, checks)
        }

      case None =>
        ko(tx, Session.Identity, response, "How come OnComplete was sent with no status?!")
    }
  }
}
