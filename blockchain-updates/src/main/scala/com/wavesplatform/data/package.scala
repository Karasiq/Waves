package com.wavesplatform

import java.time.{Duration, LocalDate, ZoneOffset}
import java.util
import java.util.Properties

import com.wavesplatform.events.protobuf.BlockchainUpdated
import org.apache.kafka.clients.consumer.{KafkaConsumer, OffsetAndMetadata, OffsetCommitCallback}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{Deserializer, IntegerDeserializer}

import scala.collection.JavaConverters._

package object data {
  object IntDeserializer extends Deserializer[Int] {
    private[this] val integerDeserializer                                      = new IntegerDeserializer
    override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = integerDeserializer.configure(configs, isKey)
    override def deserialize(topic: String, data: Array[Byte]): Int            = integerDeserializer.deserialize(topic, data)
    override def close(): Unit                                                 = ()
  }

  object BlockchainUpdatedDeserializer extends Deserializer[BlockchainUpdated] {
    override def configure(configs: util.Map[String, _], isKey: Boolean): Unit    = ()
    override def deserialize(topic: String, data: Array[Byte]): BlockchainUpdated = BlockchainUpdated.parseFrom(data)
    override def close(): Unit                                                    = ()
  }

  object Consumer {
    def create(offset: Int): KafkaConsumer[Int, BlockchainUpdated] = {
      val props = new Properties
      props.put("bootstrap.servers", util.Arrays.asList(sys.env.getOrElse("VOLK_KAFKA", "kafka-dev.wvservices.com:9092")))
      props.put("group.id", "Prem montor test1")
      props.put("client.id", "Prem montor test1")
      props.put("enable.auto.commit", "false")
      props.put("session.timeout.ms", "30000")
      props.put("max.poll.records", "10000")
      val consumer = new KafkaConsumer[Int, BlockchainUpdated](props, IntDeserializer, BlockchainUpdatedDeserializer)
      val topic    = sys.env.getOrElse("VOLK_TOPIC", "blockchain-updates-mainnet")
      if (offset != 0) {
        import scala.collection.JavaConverters._
        val partitions = consumer.partitionsFor(topic).asScala.map(pi => new TopicPartition(pi.topic(), pi.partition())).asJava
        consumer.assign(partitions)
        partitions.forEach(tp => consumer.seek(tp, offset))
      } else consumer.subscribe(util.Arrays.asList(topic))
      consumer
    }
  }

  class PollingAgent(offset: Int) {
    var isCommitPending = false
    val consumer        = Consumer.create(offset)

    def start(cb: Seq[BlockchainUpdated] => Unit): Unit = {
      while (!Thread.currentThread().isInterrupted) {
        val records = this.consumer.poll(Duration.ofMillis(1000L))
        cb(records.asScala.map(_.value()).toVector)
        isCommitPending = true
        consumer.commitAsync((_, _) => isCommitPending = false)
      }
    }

    def shutdown(): Unit = {
      if (this.isCommitPending) this.consumer.commitSync()
      this.consumer.close()
    }

    def shutdown(timeout: Duration): Unit = {
      if (this.isCommitPending) this.consumer.commitSync()
      this.consumer.close(Duration.ofMillis(timeout.toMillis))
    }
  }
}
