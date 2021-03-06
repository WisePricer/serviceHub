import helpers.{SpecBase, Scenario}
import org.serviceHub.domain._

class ServiceRepositoryTest extends SpecBase {
  val ordersService = Service("orders", subscribes = Seq("order_paid"), publishes = Seq("order_created"))
  val emailService = Service("email", subscribes = Seq("order_paid"))
  val billingService = Service("billing")
  val bamService = Service("BAM", subscribes = Seq("*"))

  val repo = new ServicesRepository(ordersService, emailService, billingService, bamService)

  "getSubscribers" should "return all subscribers" in {
    val msg = Message("order_paid")
    repo.getSubscribersFor(msg) should be (Seq(ordersService, emailService, bamService))
  }

  "getPublisher" should "return message publishing service" in {
    val msg = Message("order_created")
    repo.getPublisherOf(msg) should be (Some(ordersService))
  }

  "constructor" should "detect duplicate publishers of same message" in {
    val duplicatePublisher = Service("some_other_service", publishes = Seq("order_created"))
    val ex = intercept[NoAuthorityForMessageException] {
      new ServicesRepository(ordersService, duplicatePublisher)
    }
    ex.getMessage should include ("orders, some_other_service")
    ex.getMessage should include ("order_created")
  }

  "constructor" should "load services from services.json if no services passed" in {
    val scenarioDir = Scenario("manifest-loading-test").absoluteDir
    val repo = new ServicesRepository(scenarioDir)

    repo.services.length should be (2)
    repo.services should contain(Service("orders", Seq("order_created"), Seq("order_paid"), Seq(
      Endpoint("http://server.com/:message_type"),
      Endpoint("http://localhost/:message_type", "dev")
    )))
  }
}
