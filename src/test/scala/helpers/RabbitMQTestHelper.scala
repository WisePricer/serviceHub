package helpers

import java.io.IOException

import akka.actor.ActorRef
import akka.pattern.ask
import akka.testkit.{TestActorRef, TestKitBase}
import akka.util.Timeout
import com.rabbitmq.client._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.exceptions.TestFailedException
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{Args, Matchers, Status, Suite}
import org.serviceHub.actors.queue.MQActor._
import org.serviceHub.actors.queue.{MQActor, RabbitMQActor}
import org.serviceHub.domain.{Message, Service, ServicesRepository}

import scala.concurrent.Promise

trait RabbitMQTestHelper extends Suite with TestKitBase with Matchers with ScalaFutures {

  implicit val timeout: Timeout
  implicit val repository: ServicesRepository

  private val conFactory = new ConnectionFactory()
  private var connection: Connection = null
  private var channel: Channel = null

  protected var queueActor: ActorRef = null

  def purgeQueueIfExists(svc: Service, qType: QueueType) = {
    val qName = MQActor.resolveQueueName(svc, qType)
    try {
      channel.queueDeclarePassive(qName)
      channel.queuePurge(qName)
    }
    catch {
      // queueDeclarePassive throws IOException if the queue doesn't exist. By design.
      case ex: IOException =>
    }
  }

  override protected def runTest(testName: String, args: Args): Status = {
    connection = conFactory.newConnection()
    channel = connection.createChannel()
    for (
      svc <- repository.services;
      qType <- List(OutgoingQueue, InputQueue)
    ) purgeQueueIfExists(svc, qType)
    queueActor = TestActorRef[RabbitMQActor]

    try{
      super.runTest(testName, args)
    }
    finally {
      val terminatedFuture = (queueActor ? StopAll).mapTo[Any]
      whenReady(terminatedFuture) { x => x should be (StoppedAll)}

      channel.close()
      connection.close()
    }
  }

  def consumeMessages(queue: String, block: Message => Unit) =
    channel.basicConsume(queue, new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String,
                                  envelope: Envelope,
                                  properties: AMQP.BasicProperties,
                                  body: Array[Byte]): Unit = {
        block(MQActor.fromJsonUTF8Bytes(body))
      }
    })

  def enqueue(queue: String, msg: String): Unit =
    channel.basicPublish("", queue, null, msg.getBytes("UTF-8"))

  def enqueue(queue: String, msg: Message): Unit = enqueue(queue, MQActor.asJsonString(msg))

  // Queue matchers
  class HaveInQueue(queueType: QueueType, messages: Message*) extends Matcher[Service] {
    override def apply(left: Service): MatchResult = {
      val receivedAllMessages = Promise[Unit]()
      val receivedMessages = scala.collection.mutable.Set[Message]()
      consumeMessages(MQActor.resolveQueueName(left, queueType), {msg =>
        println(s"Received: $msg")
        receivedMessages.add(msg)
        if (receivedMessages.size == messages.size) receivedAllMessages.success(Unit)
      })

      implicit val defaultPatience = PatienceConfig(timeout = Span(1, Seconds), interval = Span(50, Millis))
      try {
        whenReady(receivedAllMessages.future)({ x =>})(config = defaultPatience)
      }
      catch {
        case cause: TestFailedException =>
      }

      val actual = receivedMessages.toSet
      val expected = messages.toSet

      MatchResult(
        actual == expected,
        s"Service ${left.name} should have receive ${expected}, but received ${actual}",
        s"Service ${left.name} should not have receive ${expected}, but received ${actual}"
      )
    }
  }
  class HaveInInputQueue(messages: Message*) extends HaveInQueue(InputQueue, messages:_*)
  class HaveInOutgoingQueue(messages: Message*) extends HaveInQueue(OutgoingQueue, messages:_*)

  def haveInInputQueue(messages: Message*) = new HaveInInputQueue(messages:_*)
  def haveInOutgoingQueue(messages: Message*) = new HaveInOutgoingQueue(messages:_*)
}
