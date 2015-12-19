// Copyright (C) 2011-2012 the original author or authors.
// See the LICENCE.txt file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package example

import org.apache.spark.storage.StorageLevel
import org.mkuthan.spark.KafkaDStreamSink._
import org.mkuthan.spark._

import scala.concurrent.duration.FiniteDuration

class WordCountJob(
                    config: WordCountJobConfig,
                    source: KafkaDStreamSource,
                    codec: StringKafkaPayloadCodec)
  extends SparkStreamingApplication with WordCount {

  override def sparkConfig: SparkApplicationConfig = config.spark

  override def sparkStreamingConfig: SparkStreamingApplicationConfig = config.sparkStreaming

  def start(): Unit = {
    withSparkStreamingContext { (sc, ssc) =>
      val input = source.createSource(ssc, config.inputTopic)

      val codecVar = sc.broadcast(codec)

      // decode Kafka payload (e.g: from Avro)
      val lines = input
        .map(codecVar.value.decode(_))
        .flatMap(_.toOption) // only decoded payloads
        .map(_._2) // only value

      val countedWords = countWords(
        ssc,
        lines,
        config.stopWords,
        config.windowDuration,
        config.slideDuration
      )

      // encode Kafka payload (e.g: to Avro)
      val output = countedWords
        .map(countedWord => codecVar.value.encode((None, countedWord.toString)))
        .flatMap(_.toOption) // only encoded payloads

      // cache to speed-up processing if action fails
      output.persist(StorageLevel.MEMORY_ONLY_SER)

      output.sendToKafka(config.sinkKafka, config.outputTopic)
    }
  }

}

object WordCountJob {

  def main(args: Array[String]): Unit = {
    val config = WordCountJobConfig()

    val source = KafkaDStreamSource(config.sourceKafka)

    val codec = StringKafkaPayloadCodec(config.stringCodec)

    val streamingJob = new WordCountJob(config, source, codec)
    streamingJob.start()
  }

}

case class WordCountJobConfig(
                               inputTopic: String,
                               outputTopic: String,
                               stopWords: Set[String],
                               windowDuration: FiniteDuration,
                               slideDuration: FiniteDuration,
                               spark: SparkApplicationConfig,
                               sparkStreaming: SparkStreamingApplicationConfig,
                               stringCodec: StringKafkaPayloadCodecConfig,
                               sourceKafka: Map[String, String],
                               sinkKafka: Map[String, String])
  extends Serializable

object WordCountJobConfig {

  import com.typesafe.config.{Config, ConfigFactory}
  import net.ceedubs.ficus.Ficus._
  import net.ceedubs.ficus.readers.ArbitraryTypeReader.arbitraryTypeValueReader

  def apply(): WordCountJobConfig = apply(ConfigFactory.load)

  def apply(applicationConfig: Config): WordCountJobConfig = {

    val config = applicationConfig.getConfig("wordCountJob")

    new WordCountJobConfig(
      config.as[String]("input.topic"),
      config.as[String]("output.topic"),
      config.as[Set[String]]("stopWords"),
      config.as[FiniteDuration]("windowDuration"),
      config.as[FiniteDuration]("slideDuration"),
      config.as[SparkApplicationConfig]("spark"),
      config.as[SparkStreamingApplicationConfig]("sparkStreaming"),
      config.as[StringKafkaPayloadCodecConfig]("stringCodec"),
      config.as[Map[String, String]]("kafkaSource"),
      config.as[Map[String, String]]("kafkaSink")
    )
  }

}

