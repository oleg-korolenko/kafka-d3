package me.milan.serdes

import scala.concurrent.duration._

import cats.effect.IO
import cats.syntax.either._
import com.sksamuel.avro4s.AvroName
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.producer.KafkaProducer
import org.scalatest.{ BeforeAndAfterEach, Matchers, WordSpec }

import me.milan.config.KafkaConfig.TopicConfig
import me.milan.config.{ ApplicationConfig, KafkaConfig }
import me.milan.domain.{ Key, Record, Topic }
import me.milan.pubsub.Pub
import me.milan.pubsub.kafka.{ KProducer, KafkaAdminClient }

class AvroSerdeIntegrationSpec extends WordSpec with Matchers with BeforeAndAfterEach {
  import AvroSerdeIntegrationSpec._

  implicit val executor = scala.concurrent.ExecutionContext.global
  implicit val cs = IO.contextShift(executor)
  implicit val timer = IO.timer(executor)

  override def beforeEach(): Unit = {
    val program = for {
      appConfig ← IO.fromEither(applicationConfig.asRight)
      kafkaAdminClient = new KafkaAdminClient[IO](appConfig.kafka)
      _ ← kafkaAdminClient.deleteAllTopics
      _ ← IO.sleep(500.millis)
    } yield ()

    program.unsafeRunTimed(10.seconds)
    ()
  }

  "AvroSerde" can {

    val kafkaAdminClient = new KafkaAdminClient[IO](applicationConfig.kafka)

    implicit val kafkaProducer: KafkaProducer[String, GenericRecord] =
      new KProducer(applicationConfig.kafka).producer

    "send two different types to the same topic" should {

      "successfully handle both record schemas" in {

        val program = for {
          _ ← kafkaAdminClient.createTopics
          _ ← IO.sleep(2.seconds)
          _ ← Pub.kafka[IO, Value1].publish(record)
          _ ← Pub.kafka[IO, OtherValue].publish(otherRecordType)
        } yield ()

        program.unsafeRunTimed(10.seconds)

      }
    }

    "send a backwards compatible record type" should {

      "successfully register the backwards compatible schema" in {

        val program = for {
          _ ← kafkaAdminClient.createTopics
          _ ← IO.sleep(2.seconds)
          _ ← Pub.kafka[IO, Value1].publish(record)
          _ ← Pub.kafka[IO, NewValue1].publish(recordWithBackwardsCompatibility)
        } yield ()

        program.unsafeRunTimed(10.seconds)

      }
    }

    "send a non backwards compatible record" should {

      "successfully receive the same record" in {

        val program = for {
          _ ← kafkaAdminClient.createTopics
          _ ← IO.sleep(2.seconds)
          _ ← Pub.kafka[IO, Value1].publish(record)
          _ ← Pub.kafka[IO, BreakingValue1].publish(recordWithBreakingCompatibility)
        } yield ()

        val thrown = the[org.apache.kafka.common.errors.SerializationException] thrownBy {
            program.unsafeRunTimed(10.seconds)
          }

        thrown.getMessage shouldBe
          "Error registering Avro schema: {\"type\":\"record\",\"name\":\"Value1\",\"namespace\":\"me.milan.serdes.AvroSerdeIntegrationSpec\",\"fields\":[{\"name\":\"newValue\",\"type\":\"int\"}]}"

      }
    }
  }

}

object AvroSerdeIntegrationSpec {

  val topic = Topic("test")

  val applicationConfig = ApplicationConfig(
    kafka = KafkaConfig(
      KafkaConfig.BootstrapServer("localhost:9092"),
      KafkaConfig.SchemaRegistryUrl(
        url = "http://localhost:8081"
      ),
      List(
        TopicConfig(
          name = topic,
          partitions = TopicConfig.Partitions(1),
          replicationFactor = TopicConfig.ReplicationFactor(1)
        )
      )
    )
  )

  trait Value
  case class Value1(value: String) extends Value
  @AvroName("Value1")
  case class NewValue1(
    value: String,
    newValue: Option[String] = None
  ) extends Value
  @AvroName("Value1")
  case class BreakingValue1(newValue: Int) extends Value
  case class OtherValue(value: String)

  val key = Key("key1")
  val value = Value1("value1")
  val newValue = NewValue1("value1", Some("test"))
  val breakingValue = BreakingValue1(1)

  val record: Record[Value1] = Record(topic, key, value, 0L)
  val otherRecordType: Record[OtherValue] = Record(topic, key, OtherValue("otherValue"), 0L)
  val recordWithBackwardsCompatibility: Record[NewValue1] = Record(topic, key, newValue, 0L)
  val recordWithBreakingCompatibility: Record[BreakingValue1] = Record(topic, key, breakingValue, 0L)

}
