package sttp.tapir.server.akkahttp

import akka.http.scaladsl.model.{MediaType => _}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.RouteDirectives
import akka.http.scaladsl.server.util.{Tuple => AkkaTuple}
import sttp.capabilities.akka.AkkaStreams
import sttp.tapir._
import sttp.tapir.monad.FutureMonadError
import sttp.tapir.server.{ServerDefaults, ServerEndpoint}
import sttp.tapir.typelevel.ParamsToTuple

import scala.concurrent.Future
import scala.util.{Failure, Success}

class EndpointToAkkaServer(serverOptions: AkkaHttpServerOptions) {
  def toDirective[I, E, O, T](e: Endpoint[I, E, O, AkkaStreams])(implicit paramsToTuple: ParamsToTuple.Aux[I, T]): Directive[T] = {
    implicit val tIsAkkaTuple: AkkaTuple[T] = AkkaTuple.yes
    toDirective1(e).flatMap { values => tprovide(paramsToTuple.toTuple(values)) }
  }

  def toRoute[I, E, O](se: ServerEndpoint[I, E, O, AkkaStreams, Future]): Route = {
    toDirective1(se.endpoint) { values =>
      extractLog { log =>
        mapResponse(resp => { serverOptions.logRequestHandling.requestHandled(se.endpoint, resp.status.intValue())(log); resp }) {
          extractExecutionContext { ec =>
            onComplete(se.logic(new FutureMonadError()(ec))(values)) {
              case Success(Left(v))  => OutputToAkkaRoute(ServerDefaults.StatusCodes.error.code, se.endpoint.errorOutput, v)
              case Success(Right(v)) => OutputToAkkaRoute(ServerDefaults.StatusCodes.success.code, se.endpoint.output, v)
              case Failure(e) =>
                serverOptions.logRequestHandling.logicException(se.endpoint, e)(log)
                throw e
            }
          }
        }
      }
    }
  }

  def toRoute(serverEndpoints: List[ServerEndpoint[_, _, _, AkkaStreams, Future]]): Route = {
    serverEndpoints.map(se => toRoute(se)).foldLeft(RouteDirectives.reject: Route)(_ ~ _)
  }

  private def toDirective1[I, E, O](e: Endpoint[I, E, O, AkkaStreams]): Directive1[I] = new EndpointToAkkaDirective(serverOptions)(e)
}
