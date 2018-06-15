package ch.epfl.bluebrain.nexus.admin.client

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.TestKit
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.admin.client.config.AdminConfig
import ch.epfl.bluebrain.nexus.admin.refined.project.ProjectReference
import ch.epfl.bluebrain.nexus.commons.http.HttpClient.UntypedHttpClient
import ch.epfl.bluebrain.nexus.commons.http.UnexpectedUnsuccessfulHttpResponse
import ch.epfl.bluebrain.nexus.commons.test.Resources.contentOf
import ch.epfl.bluebrain.nexus.commons.types.HttpRejection.UnauthorizedAccess
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.UserRef
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.rdf.Iri
import eu.timepit.refined.auto._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, Matchers, OptionValues, WordSpecLike}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class AdminClientSpec
    extends TestKit(ActorSystem("IamClientSpec"))
    with WordSpecLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll
    with OptionValues {

  private val base   = Uri("http://localhost/v1/projects/")
  private val config = AdminConfig(base)

  private implicit val ec: ExecutionContext = system.dispatcher
  private implicit val mt: Materializer     = ActorMaterializer()

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(6.seconds, 300.milliseconds)

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  "An AdminClient" should {

    "return an existing project from upstream" in {
      val name: ProjectReference = ProjectReference("orgname", "projectname")
      val mockedResponse = Future.successful(
        HttpResponse(
          entity = HttpEntity(ContentTypes.`application/json`, contentOf("/project.json")),
          status = StatusCodes.OK
        ))
      implicit val credentials: Option[AuthToken] = Some(AuthToken("validToken"))
      implicit val cl: UntypedHttpClient[Future] =
        mockedClient(base.copy(path = base.path + name.show), credentials, mockedResponse)
      val adminClient = AdminClient(config)

      val project = adminClient.getProject(name).futureValue
      project.uuid shouldEqual "350df698-6813-11e8-adc0-fa7ae01bbebc"
      project.deprecated shouldEqual false
      project.rev shouldEqual 3
      project.name shouldEqual "projectname"
      project.base shouldEqual "http://localhost/v1/resources/"
      project.prefixMappings shouldEqual Map(
        "nxv-projectname"    -> Iri.absolute("https://nexus.example.com/vocabs/nexus/core/terms/v0.1.0/").toOption.value,
        "person-projectname" -> Iri.absolute("https://shapes-registry.org/commons/person").toOption.value
      )
    }

    "return ACLs from upstream for an existing project" in {
      val name: ProjectReference = ProjectReference("orgname", "projectname")
      val mockedResponse = Future.successful(
        HttpResponse(
          entity = HttpEntity(ContentTypes.`application/json`, contentOf("/project-acls.json")),
          status = StatusCodes.OK
        ))
      implicit val credentials: Option[AuthToken] = Some(AuthToken("validToken"))
      implicit val cl: UntypedHttpClient[Future] =
        mockedClient(
          base.copy(path = base.path + s"${name.show}/acls").withQuery(Query("parents" -> "true", "self" -> "false")),
          credentials,
          mockedResponse)
      val adminClient = AdminClient(config)

      val project = adminClient.getProjectAcls(name, true, false).futureValue
      project.acl shouldEqual List(
        FullAccessControl(
          UserRef("bbp-test", "ca88f6d1-4f71-4fc0-b023-de82b8afdc30"),
          Address("projectname"),
          Permissions(Permission("projects/read"))
        ))
    }

    "work without a trailing slash in its config" in {
      val name: ProjectReference = ProjectReference("orgname", "projectname")
      val mockedResponse = Future.successful(
        HttpResponse(
          entity = HttpEntity(ContentTypes.`application/json`, contentOf("/project.json")),
          status = StatusCodes.OK
        ))
      implicit val credentials: Option[AuthToken] = Some(AuthToken("validToken"))
      implicit val cl: UntypedHttpClient[Future] =
        mockedClient(base.copy(path = base.path + name.show), credentials, mockedResponse)
      val adminClient = AdminClient(AdminConfig(Uri("http://localhost/v1/projects")))

      val project = adminClient.getProject(name).futureValue
      project.name shouldEqual "projectname"
    }

    "forward unauthorized access errors" in {
      val name: ProjectReference = ProjectReference("orgname", "unauthorized")

      val mockedResponse = Future.successful(
        HttpResponse(
          entity = HttpEntity(ContentTypes.`application/json`, contentOf("/unauthorized.json")),
          status = StatusCodes.Unauthorized
        ))
      implicit val cl: UntypedHttpClient[Future] =
        mockedClient(base.copy(path = base.path + name.show), None, mockedResponse)
      val adminClient = AdminClient(config)

      val error = adminClient.getProject(name)(None).failed.futureValue
      error shouldBe a[UnauthorizedAccess.type]
    }

    "handle unexpected upstream errors" in {
      val name: ProjectReference = ProjectReference("orgname", "nonexistent")

      val mockedResponse = Future.successful(
        HttpResponse(
          entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, "Resource not found"),
          status = StatusCodes.NotFound
        ))
      implicit val cl: UntypedHttpClient[Future] =
        mockedClient(base.copy(path = base.path + name.show), None, mockedResponse)
      val adminClient = AdminClient(config)

      val error = adminClient.getProject(name)(None).failed.futureValue
      error shouldBe a[UnexpectedUnsuccessfulHttpResponse]
    }
  }

  def mockedClient(expectedUri: Uri,
                   expectedAuth: Option[AuthToken],
                   response: Future[HttpResponse]): UntypedHttpClient[Future] =
    new UntypedHttpClient[Future] {
      override def apply(req: HttpRequest): Future[HttpResponse] =
        if (req.uri == expectedUri) {
          (expectedAuth, req.header[Authorization]) match {
            case (None, None) =>
              response
            case (Some(AuthToken(t1)), Some(Authorization(OAuth2BearerToken(t2)))) if t1 == t2 =>
              response
            case _ =>
              fail("Wrong request credentials")
          }
        } else {
          fail(s"Wrong request uri: ${req.uri} expected: $expectedUri")
        }

      override def discardBytes(entity: HttpEntity): Future[HttpMessage.DiscardedEntity] =
        Future.apply(entity.discardBytes())

      override def toString(entity: HttpEntity): Future[String] = Future.apply("")
    }
}
