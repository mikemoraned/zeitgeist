package com.houseofmoran.zeitgeist

import ch.hsr.geohash.GeoHash
import com.houseofmoran.spark.twitter.TwitterStreamSource
import org.apache.spark._
import org.apache.spark.streaming.StreamingContext._
import org.apache.spark.streaming._
import org.eclipse.jetty.server.handler.{HandlerList, ResourceHandler}
import org.eclipse.jetty.server.{Handler, Server}
import twitter4j.GeoLocation

import scala.util.Properties

object TwitterVisApp {

  def toGeoHash(location: GeoLocation, depth: Int): String = {
    GeoHash.withCharacterPrecision(location.getLatitude, location.getLongitude, depth).toBase32;
  }

  def main(args: Array[String]): Unit = {
    val conf = new SparkConf().setAppName("TwitterVisApp").setMaster("local[*]")
    val sc = new SparkContext(conf)
    val windowLength = Seconds(10)
    implicit val ssc = new StreamingContext(sc, windowLength)

    val twitterStream =
      if (args.length > 0) {
        println("Reading auth from file")
        TwitterStreamSource.streamFromAuthIn(args(0))
      }
      else {
        println("Reading auth from env")
        TwitterStreamSource.streamFromEnv()
      }

    val sample = new TweetSample
    val geoHistory = new GeoHistory

    val sampleHandler = new SnapshotHandler(Map(
      ("sample" -> sample), ("geohistory" -> geoHistory)))

    val geoStatuses = twitterStream.
      filter(status => status.getGeoLocation() != null)

    geoStatuses.map(status => (status.getId, status)).
      foreachRDD( rdd => sample.newWindow(rdd) )

    geoStatuses.map(status => (toGeoHash(status.getGeoLocation(), 3), 1)).
      reduceByKey(_ + _).
      foreachRDD( rdd => geoHistory.newCounts(rdd) )

    val server = new Server(Properties.envOrElse("PORT", "8080").toInt)

    val resources = new ResourceHandler()
    resources.setWelcomeFiles(Array[String]("index.html"))
    resources.setResourceBase("./src/main/resources")

    val handlers = new HandlerList()
    handlers.setHandlers(Array[Handler]( resources, sampleHandler ))

    server.setHandler(handlers)
    server.start()

    ssc.start()
    ssc.awaitTermination()
  }
}
