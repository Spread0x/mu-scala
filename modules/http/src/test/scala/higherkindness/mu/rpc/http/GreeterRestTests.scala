/*
 * Copyright 2017-2019 47 Degrees, LLC. <http://www.47deg.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package higherkindness.mu.rpc.http

import cats.effect.{IO, _}
import fs2.Stream
import higherkindness.mu.http.{ResponseError, UnexpectedError}
import higherkindness.mu.rpc.common.RpcBaseTestSuite
import higherkindness.mu.http.implicits._
import higherkindness.mu.rpc.protocol.Empty
import io.circe.Decoder.Result
import io.circe.{Decoder, HCursor, Json}
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.{Status, _}
import org.http4s.circe._
import org.http4s.client.UnexpectedStatus
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.server.blaze._
import org.scalatest._
import org.http4s.implicits._
import org.http4s.server.Router

import scala.concurrent.ExecutionContext.Implicits.global

class GreeterRestTests extends RpcBaseTestSuite with BeforeAndAfter {

  val Hostname = "localhost"
  val Port     = 8080

  val serviceUri: Uri = Uri.unsafeFromString(s"http://$Hostname:$Port")

  val UnaryServicePrefix = "UnaryGreeter"
  val Fs2ServicePrefix   = "Fs2Greeter"

  implicit val cs: ContextShift[IO] = IO.contextShift(global)
  implicit val timer: Timer[IO]     = IO.timer(global)

  implicit val unaryHandlerIO = new UnaryGreeterHandler[IO]
  implicit val fs2HandlerIO   = new Fs2GreeterHandler[IO]

  val unaryService: HttpRoutes[IO] = new UnaryGreeterRestService[IO].service
  val fs2Service: HttpRoutes[IO]   = new Fs2GreeterRestService[IO].service

  val server: BlazeServerBuilder[IO] = BlazeServerBuilder[IO]
    .bindHttp(Port, Hostname)
    .withHttpApp(Router(
      s"/$UnaryServicePrefix" -> unaryService,
      s"/$Fs2ServicePrefix"   -> fs2Service).orNotFound)

  var serverTask: Fiber[IO, Nothing] = _
  before(serverTask = server.resource.use(_ => IO.never).start.unsafeRunSync())
  after(serverTask.cancel.unsafeRunSync)

  "REST Server" should {

    "serve a GET request" in {
      val request  = Request[IO](Method.GET, serviceUri / UnaryServicePrefix / "getHello")
      val response = BlazeClientBuilder[IO](global).resource.use(_.expect[Json](request))
      response.unsafeRunSync() shouldBe HelloResponse("hey").asJson
    }

    "serve a OPTIONS request" in {
      val request = Request[IO](Method.OPTIONS, serviceUri / UnaryServicePrefix / "optionsHello")
      val response =
        BlazeClientBuilder[IO](global).resource.use(_.expect[Json](request))
      response.unsafeRunSync() shouldBe HelloResponse("Options: Hey").asJson
    }

    "serve a HEAD request" in {
      val request = Request[IO](Method.HEAD, serviceUri / UnaryServicePrefix / "headHello")

      val response: IO[Status] = (for {
        client <- BlazeClientBuilder[IO](global).resource
        resp   <- client.run(request)
      } yield resp.status).allocated.map(_._1)

      response.unsafeRunSync() shouldBe Status.NoContent
    }

    "serve a TRACE request" in {
      val request = Request[IO](Method.TRACE, serviceUri / UnaryServicePrefix / "traceHello")

      val response: IO[Status] = (for {
        client <- BlazeClientBuilder[IO](global).resource
        resp   <- client.run(request)
      } yield resp.status).allocated.map(_._1)

      response.unsafeRunSync() shouldBe Status.NoContent
    }

    "serve a CONNECT request" in {
      val request = Request[IO](Method.CONNECT, serviceUri / UnaryServicePrefix / "connectHello")
      val response =
        BlazeClientBuilder[IO](global).resource.use(_.expect[Json](request))
      response.unsafeRunSync() shouldBe HelloResponse("Connect: Hey").asJson
    }

    "serve a PUT request" in {
      val request = Request[IO](Method.PUT, serviceUri / UnaryServicePrefix / "putHello")

      val response: IO[Status] = (for {
        client <- BlazeClientBuilder[IO](global).resource
        resp   <- client.run(request.withEntity(HelloRequest("hey").asJson))
      } yield resp.status).allocated.map(_._1)

      response.unsafeRunSync() shouldBe Status.Accepted
    }

    "serve a PATCH request" in {
      val request = Request[IO](Method.PATCH, serviceUri / UnaryServicePrefix / "patchHello")

      val response: IO[Status] = (for {
        client <- BlazeClientBuilder[IO](global).resource
        resp   <- client.run(request.withEntity(HelloRequest("hey").asJson))
      } yield resp.status).allocated.map(_._1)

      response.unsafeRunSync() shouldBe Status.Accepted
    }

    "serve a DELETE request" in {
      val request     = Request[IO](Method.DELETE, serviceUri / UnaryServicePrefix / "deleteHello")
      val requestBody = HelloRequest("hey").asJson
      val response =
        BlazeClientBuilder[IO](global).resource.use(_.expect[Json](request.withEntity(requestBody)))
      response.unsafeRunSync() shouldBe HelloResponse("Delete: Hey").asJson
    }

    "serve a POST request" in {
      val request     = Request[IO](Method.POST, serviceUri / UnaryServicePrefix / "sayHello")
      val requestBody = HelloRequest("hey").asJson
      val response =
        BlazeClientBuilder[IO](global).resource.use(_.expect[Json](request.withEntity(requestBody)))
      response.unsafeRunSync() shouldBe HelloResponse("hey").asJson
    }

    "return a 400 Bad Request for a malformed unary POST request" in {
      val request     = Request[IO](Method.POST, serviceUri / UnaryServicePrefix / "sayHello")
      val requestBody = "{"
      val response =
        BlazeClientBuilder[IO](global).resource.use(_.expect[Json](request.withEntity(requestBody)))
      the[UnexpectedStatus] thrownBy response.unsafeRunSync() shouldBe UnexpectedStatus(
        Status.BadRequest)
    }

    "return a 400 Bad Request for a malformed streaming POST request" in {
      val request     = Request[IO](Method.POST, serviceUri / Fs2ServicePrefix / "sayHellos")
      val requestBody = "{"
      val response =
        BlazeClientBuilder[IO](global).resource.use(_.expect[Json](request.withEntity(requestBody)))
      the[UnexpectedStatus] thrownBy response.unsafeRunSync() shouldBe UnexpectedStatus(
        Status.BadRequest)
    }

  }

  val unaryServiceClient: UnaryGreeterRestClient[IO] =
    new UnaryGreeterRestClient[IO](serviceUri / UnaryServicePrefix)
  val fs2ServiceClient: Fs2GreeterRestClient[IO] =
    new Fs2GreeterRestClient[IO](serviceUri / Fs2ServicePrefix)

  "REST Service" should {

    "serve a GET request" in {
      val response = BlazeClientBuilder[IO](global).resource.use(unaryServiceClient.getHello()(_))
      response.unsafeRunSync() shouldBe HelloResponse("hey")
    }

    "serve a OPTIONS request" in {
      val response =
        BlazeClientBuilder[IO](global).resource.use(unaryServiceClient.optionsHello()(_))
      response.unsafeRunSync() shouldBe HelloResponse("Options: Hey")
    }

    "serve a HEAD request" in {
      val status: IO[Status] =
        BlazeClientBuilder[IO](global).resource.use(unaryServiceClient.headHello()(_)).map(_.status)
      status.unsafeRunSync() shouldBe Status.NoContent
    }

    "serve a TRACE request" in {
      val status: IO[Status] =
        BlazeClientBuilder[IO](global).resource
          .use(unaryServiceClient.traceHello()(_))
          .map(_.status)
      status.unsafeRunSync() shouldBe Status.NoContent
    }

    "serve a CONNECT request" in {
      val response =
        BlazeClientBuilder[IO](global).resource.use(unaryServiceClient.connectHello()(_))
      response.unsafeRunSync() shouldBe HelloResponse("Connect: Hey")
    }

    "serve a PUT request" in {
      val status: IO[Status] =
        BlazeClientBuilder[IO](global).resource
          .use(unaryServiceClient.putHello(HelloRequest("hey"))(_))
          .map(_.status)
      status.unsafeRunSync() shouldBe Status.Accepted
    }

    "serve a PATCH request" in {
      val status: IO[Status] =
        BlazeClientBuilder[IO](global).resource
          .use(unaryServiceClient.patchHello(HelloRequest("hey"))(_))
          .map(_.status)
      status.unsafeRunSync() shouldBe Status.Accepted
    }

    "serve a unary DELETE request" in {
      val response = BlazeClientBuilder[IO](global).resource
        .use(unaryServiceClient.deleteHello(HelloRequest("hey"))(_))
      response.unsafeRunSync() shouldBe HelloResponse("Delete: Hey")
    }

    "serve a unary POST request" in {
      val request = HelloRequest("hey")
      val response =
        BlazeClientBuilder[IO](global).resource.use(unaryServiceClient.sayHello(request)(_))
      response.unsafeRunSync() shouldBe HelloResponse("hey")
    }

    "handle a raised gRPC exception in a unary POST request" in {
      val request = HelloRequest("SRE")
      val response =
        BlazeClientBuilder[IO](global).resource.use(unaryServiceClient.sayHello(request)(_))
      the[ResponseError] thrownBy response.unsafeRunSync() shouldBe ResponseError(
        Status.BadRequest,
        Some("INVALID_ARGUMENT: SRE"))
    }

    "handle a raised non-gRPC exception in a unary POST request" in {
      val request = HelloRequest("RTE")
      val response =
        BlazeClientBuilder[IO](global).resource.use(unaryServiceClient.sayHello(request)(_))
      the[ResponseError] thrownBy response.unsafeRunSync() shouldBe ResponseError(
        Status.InternalServerError,
        Some("RTE"))
    }

    "handle a thrown exception in a unary POST request" in {
      val request = HelloRequest("TR")
      val response =
        BlazeClientBuilder[IO](global).resource.use(unaryServiceClient.sayHello(request)(_))
      the[ResponseError] thrownBy response.unsafeRunSync() shouldBe ResponseError(
        Status.InternalServerError)
    }

    "serve a POST request with fs2 streaming request" in {
      val requests = Stream(HelloRequest("hey"), HelloRequest("there"))
      val response =
        BlazeClientBuilder[IO](global).resource.use(fs2ServiceClient.sayHellos(requests)(_))
      response.unsafeRunSync() shouldBe HelloResponse("hey, there")
    }

    "serve a POST request with empty fs2 streaming request" in {
      val requests = Stream.empty
      val response =
        BlazeClientBuilder[IO](global).resource.use(fs2ServiceClient.sayHellos(requests)(_))
      response.unsafeRunSync() shouldBe HelloResponse("")
    }

    "serve a POST request with fs2 streaming response" in {
      val request = HelloRequest("hey")
      val responses =
        BlazeClientBuilder[IO](global).stream.flatMap(fs2ServiceClient.sayHelloAll(request)(_))
      responses.compile.toList
        .unsafeRunSync() shouldBe List(HelloResponse("hey"), HelloResponse("hey"))
    }

    "handle errors with fs2 streaming response" in {
      val request = HelloRequest("")
      val responses =
        BlazeClientBuilder[IO](global).stream.flatMap(fs2ServiceClient.sayHelloAll(request)(_))
      the[UnexpectedError] thrownBy responses.compile.toList
        .unsafeRunSync() should have message "java.lang.IllegalArgumentException: empty greeting"
    }

    "serve a POST request with bidirectional fs2 streaming" in {
      val requests = Stream(HelloRequest("hey"), HelloRequest("there"))
      val responses =
        BlazeClientBuilder[IO](global).stream.flatMap(fs2ServiceClient.sayHellosAll(requests)(_))
      responses.compile.toList
        .unsafeRunSync() shouldBe List(HelloResponse("hey"), HelloResponse("there"))
    }

    "serve an empty POST request with bidirectional fs2 streaming" in {
      val requests = Stream.empty
      val responses =
        BlazeClientBuilder[IO](global).stream.flatMap(fs2ServiceClient.sayHellosAll(requests)(_))
      responses.compile.toList.unsafeRunSync() shouldBe Nil
    }

  }
}
