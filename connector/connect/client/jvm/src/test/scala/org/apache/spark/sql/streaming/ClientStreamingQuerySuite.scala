/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.streaming

import java.io.{File, FileWriter}
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters._

import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.Futures.timeout
import org.scalatest.time.SpanSugar._

import org.apache.spark.SparkException
import org.apache.spark.api.java.function.VoidFunction2
import org.apache.spark.internal.Logging
import org.apache.spark.sql.{DataFrame, ForeachWriter, Row, SparkSession}
import org.apache.spark.sql.functions.{col, udf, window}
import org.apache.spark.sql.streaming.StreamingQueryListener.{QueryIdleEvent, QueryProgressEvent, QueryStartedEvent, QueryTerminatedEvent}
import org.apache.spark.sql.test.{QueryTest, SQLHelper}
import org.apache.spark.util.SparkFileUtils

class ClientStreamingQuerySuite extends QueryTest with SQLHelper with Logging {

  test("Streaming API with windowed aggregate query") {
    // This verifies standard streaming API by starting a streaming query with windowed count.
    withSQLConf(
      "spark.sql.shuffle.partitions" -> "1" // Avoid too many reducers.
    ) {
      val readDF = spark.readStream
        .format("rate")
        .option("rowsPerSecond", "10")
        .option("numPartitions", "1")
        .load()

      // Verify schema (results in sending an RPC)
      assert(readDF.schema.toDDL == "timestamp TIMESTAMP,value BIGINT")

      val countsDF = readDF
        .withWatermark("timestamp", "10 seconds")
        .groupBy(window(col("timestamp"), "5 seconds"))
        .count()
        .selectExpr("window.start as timestamp", "count as num_events")

      assert(countsDF.schema.toDDL == "timestamp TIMESTAMP,num_events BIGINT NOT NULL")

      // Start the query
      val queryName = "sparkConnectStreamingQuery"

      val query = countsDF.writeStream
        .format("memory")
        .queryName(queryName)
        .trigger(Trigger.ProcessingTime("1 second"))
        .start()

      try {
        // Verify some of the API.
        assert(query.isActive)

        eventually(timeout(30.seconds)) {
          assert(query.status.isDataAvailable)
          assert(query.recentProgress.nonEmpty) // Query made progress.
        }

        val lastProgress = query.lastProgress
        assert(lastProgress != null)
        assert(lastProgress.name == queryName)
        assert(!lastProgress.durationMs.isEmpty)
        assert(!lastProgress.eventTime.isEmpty)
        assert(lastProgress.stateOperators.nonEmpty)
        assert(
          lastProgress.stateOperators.head.customMetrics.keySet().asScala == Set(
            "loadedMapCacheHitCount",
            "loadedMapCacheMissCount",
            "stateOnCurrentVersionSizeBytes"))
        assert(lastProgress.sources.nonEmpty)
        assert(lastProgress.sink.description == "MemorySink")
        assert(lastProgress.observedMetrics.isEmpty)

        query.recentProgress.foreach { p =>
          assert(p.id == lastProgress.id)
          assert(p.runId == lastProgress.runId)
          assert(p.name == lastProgress.name)
        }

        query.explain() // Prints the plan to console.
        // Consider verifying explain output by capturing stdout similar to
        // test("Dataset explain") in ClientE2ETestSuite.

      } finally {
        // Don't wait for any processed data. Otherwise the test could take multiple seconds.
        query.stop()

        // The query should still be accessible after stopped.
        assert(!query.isActive)
        assert(query.recentProgress.nonEmpty)
      }
    }
  }

  test("Streaming table API") {
    withSQLConf(
      "spark.sql.shuffle.partitions" -> "1" // Avoid too many reducers.
    ) {
      spark.sql("DROP TABLE IF EXISTS my_table").collect()

      withTempPath { ckpt =>
        val q1 = spark.readStream
          .format("rate")
          .load()
          .writeStream
          .option("checkpointLocation", ckpt.getCanonicalPath)
          .toTable("my_table")

        val q2 = spark.readStream
          .table("my_table")
          .writeStream
          .format("memory")
          .queryName("my_sink")
          .start()

        try {
          q1.processAllAvailable()
          q2.processAllAvailable()
          eventually(timeout(30.seconds)) {
            assert(spark.table("my_sink").count() > 0)
          }
        } finally {
          q1.stop()
          q2.stop()
          spark.sql("DROP TABLE my_table")
        }
      }
    }
  }

  test("awaitTermination") {
    withSQLConf(
      "spark.sql.shuffle.partitions" -> "1" // Avoid too many reducers.
    ) {
      val q = spark.readStream
        .format("rate")
        .load()
        .writeStream
        .format("memory")
        .queryName("test")
        .start()

      val start = System.nanoTime
      val terminated = q.awaitTermination(500)
      val end = System.nanoTime
      assert((end - start) / 1e6 >= 500)
      assert(!terminated)

      q.stop()
      eventually(timeout(1.minute)) {
        q.awaitTermination()
      }
    }
  }

  test("throw exception in streaming") {
    try {
      val session = spark
      import session.implicits._

      val checkForTwo = udf((value: Int) => {
        if (value == 2) {
          throw new RuntimeException("Number 2 encountered!")
        }
        value
      })

      val query = spark.readStream
        .format("rate")
        .option("rowsPerSecond", "1")
        .load()
        .select(checkForTwo($"value").as("checkedValue"))
        .writeStream
        .outputMode("append")
        .format("console")
        .start()

      val exception = intercept[StreamingQueryException] {
        query.awaitTermination()
      }

      assert(exception.getErrorClass != null)
      assert(exception.getMessageParameters().get("id") == query.id.toString)
      assert(exception.getMessageParameters().get("runId") == query.runId.toString)
      assert(!exception.getMessageParameters().get("startOffset").isEmpty)
      assert(!exception.getMessageParameters().get("endOffset").isEmpty)
      assert(exception.getCause.isInstanceOf[SparkException])
      assert(exception.getCause.getCause.isInstanceOf[SparkException])
      assert(
        exception.getCause.getCause.getMessage
          .contains("java.lang.RuntimeException: Number 2 encountered!"))
    } finally {
      spark.streams.resetTerminated()
    }
  }

  test("throw exception in streaming, check with StreamingQueryManager") {
    val session = spark
    import session.implicits._

    val checkForTwo = udf((value: Int) => {
      if (value == 2) {
        throw new RuntimeException("Number 2 encountered!")
      }
      value
    })

    val query = spark.readStream
      .format("rate")
      .option("rowsPerSecond", "1")
      .load()
      .select(checkForTwo($"value").as("checkedValue"))
      .writeStream
      .outputMode("append")
      .format("console")
      .start()

    val exception = intercept[StreamingQueryException] {
      spark.streams.awaitAnyTermination()
    }

    assert(exception.getErrorClass != null)
    assert(exception.getMessageParameters().get("id") == query.id.toString)
    assert(exception.getMessageParameters().get("runId") == query.runId.toString)
    assert(!exception.getMessageParameters().get("startOffset").isEmpty)
    assert(!exception.getMessageParameters().get("endOffset").isEmpty)
    assert(exception.getCause.isInstanceOf[SparkException])
    assert(exception.getCause.getCause.isInstanceOf[SparkException])
    assert(
      exception.getCause.getCause.getMessage
        .contains("java.lang.RuntimeException: Number 2 encountered!"))
  }

  test("foreach Row") {
    val writer = new TestForeachWriter[Row]

    val df = spark.readStream
      .format("rate")
      .option("rowsPerSecond", "10")
      .load()

    val query = df.writeStream
      .foreach(writer)
      .outputMode("update")
      .start()

    assert(query.isActive)
    assert(query.exception.isEmpty)

    query.stop()
  }

  test("foreach Int") {
    val session: SparkSession = spark
    import session.implicits._

    val writer = new TestForeachWriter[Int]

    val df = spark.readStream
      .format("rate")
      .option("rowsPerSecond", "10")
      .load()

    val query = df
      .selectExpr("CAST(value AS INT)")
      .as[Int]
      .writeStream
      .foreach(writer)
      .outputMode("update")
      .start()

    assert(query.isActive)
    assert(query.exception.isEmpty)

    query.stop()
  }

  test("foreach Custom class") {
    val session: SparkSession = spark
    import session.implicits._

    val writer = new TestForeachWriter[TestClass]
    val df = spark.readStream
      .format("rate")
      .option("rowsPerSecond", "10")
      .load()

    val query = df
      .selectExpr("CAST(value AS INT)")
      .as[TestClass]
      .writeStream
      .foreach(writer)
      .outputMode("update")
      .start()

    assert(query.isActive)
    assert(query.exception.isEmpty)

    query.stop()
  }

  test("streaming query manager") {
    assert(spark.streams.active.isEmpty)
    val q = spark.readStream
      .format("rate")
      .load()
      .writeStream
      .format("console")
      .start()

    assert(q.name == null)
    val q1 = spark.streams.get(q.id)
    val q2 = spark.streams.active(0)
    assert(q.id == q1.id && q.id == q2.id)
    assert(q.runId == q1.runId && q.runId == q2.runId)
    assert(q1.name == null && q2.name == null)

    spark.streams.resetTerminated()
    val start = System.nanoTime
    // Same setting as in test_query_manager_await_termination in test_streaming.py
    val terminated = spark.streams.awaitAnyTermination(2600)
    val end = System.nanoTime
    assert((end - start) >= TimeUnit.MILLISECONDS.toNanos(2000))
    assert(!terminated)

    q.stop()
    assert(!q1.isActive)

    assert(spark.streams.get(q.id) == null)
  }

  test("streaming query listener") {
    testStreamingQueryListener(new EventCollectorV1, "_v1")
    testStreamingQueryListener(new EventCollectorV2, "_v2")
  }

  private def testStreamingQueryListener(
      listener: StreamingQueryListener,
      tablePostfix: String): Unit = {
    assert(spark.streams.listListeners().length == 0)

    spark.streams.addListener(listener)

    val q = spark.readStream
      .format("rate")
      .load()
      .writeStream
      .format("console")
      .start()

    try {
      q.processAllAvailable()
      eventually(timeout(30.seconds)) {
        assert(q.isActive)

        assert(!spark.table(s"listener_start_events$tablePostfix").toDF().isEmpty)
        assert(!spark.table(s"listener_progress_events$tablePostfix").toDF().isEmpty)
      }
    } finally {
      q.stop()

      eventually(timeout(30.seconds)) {
        assert(!q.isActive)
        assert(!spark.table(s"listener_terminated_events$tablePostfix").toDF().isEmpty)
      }

      spark.sql(s"DROP TABLE IF EXISTS listener_start_events$tablePostfix")
      spark.sql(s"DROP TABLE IF EXISTS listener_progress_events$tablePostfix")
      spark.sql(s"DROP TABLE IF EXISTS listener_terminated_events$tablePostfix")
    }

    // List listeners after adding a new listener, length should be 1.
    val listeners = spark.streams.listListeners()
    assert(listeners.length == 1)

    // Add listener1 as another instance of EventCollector and validate
    val listener1 = new EventCollectorV2
    spark.streams.addListener(listener1)
    assert(spark.streams.listListeners().length == 2)
    spark.streams.removeListener(listener1)
    assert(spark.streams.listListeners().length == 1)

    // Add the same listener again and validate, this aims to verify the listener cache
    // is correctly stored and cleaned.
    spark.streams.addListener(listener)
    assert(spark.streams.listListeners().length == 2)
    spark.streams.removeListener(listener)
    assert(spark.streams.listListeners().length == 1)

    // Remove the listener, length should be 1.
    spark.streams.removeListener(listener)
    assert(spark.streams.listListeners().length == 0)
  }

  test("foreachBatch") {
    // Starts a streaming query with a foreachBatch function, which writes batchId and row count
    // to a temp view. The test verifies that the view is populated with data.

    val viewName = "test_view"
    val tableName = s"global_temp.$viewName"

    withTable(tableName) {
      val q = spark.readStream
        .format("rate")
        .option("rowsPerSecond", "10")
        .option("numPartitions", "1")
        .load()
        .writeStream
        .foreachBatch(new ForeachBatchFn(viewName))
        .start()

      eventually(timeout(30.seconds)) { // Wait for first progress.
        assert(q.lastProgress != null, "Failed to make progress")
        assert(q.lastProgress.numInputRows > 0)
      }

      eventually(timeout(30.seconds)) {
        // There should be row(s) in temporary view created by foreachBatch.
        val rows = spark
          .sql(s"select * from $tableName")
          .collect()
          .toSeq
        assert(rows.size > 0)
        logInfo(s"Rows in $tableName: $rows")
      }

      q.stop()
    }
  }
}

class TestForeachWriter[T] extends ForeachWriter[T] {
  var fileWriter: FileWriter = _
  var path: File = _

  def open(partitionId: Long, version: Long): Boolean = {
    path = SparkFileUtils.createTempDir()
    fileWriter = new FileWriter(path, true)
    true
  }

  def process(row: T): Unit = {
    fileWriter.write(row.toString)
    fileWriter.write("\n")
  }

  def close(errorOrNull: Throwable): Unit = {
    fileWriter.close()
    SparkFileUtils.deleteRecursively(path)
  }
}

case class TestClass(value: Int) {
  override def toString: String = value.toString
}

abstract class EventCollector extends StreamingQueryListener {
  private lazy val spark = SparkSession.builder().getOrCreate()

  protected def tablePostfix: String

  protected def handleOnQueryStarted(event: QueryStartedEvent): Unit = {
    val df = spark.createDataFrame(Seq((event.json, 0)))
    df.write.mode("append").saveAsTable(s"listener_start_events$tablePostfix")
  }

  protected def handleOnQueryProgress(event: QueryProgressEvent): Unit = {
    val df = spark.createDataFrame(Seq((event.json, 0)))
    df.write.mode("append").saveAsTable(s"listener_progress_events$tablePostfix")
  }

  protected def handleOnQueryTerminated(event: QueryTerminatedEvent): Unit = {
    val df = spark.createDataFrame(Seq((event.json, 0)))
    df.write.mode("append").saveAsTable(s"listener_terminated_events$tablePostfix")
  }
}

/**
 * V1: Initial interface of StreamingQueryListener containing methods `onQueryStarted`,
 * `onQueryProgress`, `onQueryTerminated`. It is prior to Spark 3.5.
 */
class EventCollectorV1 extends EventCollector {
  override protected def tablePostfix: String = "_v1"

  override def onQueryStarted(event: QueryStartedEvent): Unit = handleOnQueryStarted(event)

  override def onQueryProgress(event: QueryProgressEvent): Unit = handleOnQueryProgress(event)

  override def onQueryTerminated(event: QueryTerminatedEvent): Unit =
    handleOnQueryTerminated(event)
}

/**
 * V2: The interface after the method `onQueryIdle` is added. It is Spark 3.5+.
 */
class EventCollectorV2 extends EventCollector {
  override protected def tablePostfix: String = "_v2"

  override def onQueryStarted(event: QueryStartedEvent): Unit = handleOnQueryStarted(event)

  override def onQueryProgress(event: QueryProgressEvent): Unit = handleOnQueryProgress(event)

  override def onQueryIdle(event: QueryIdleEvent): Unit = {}

  override def onQueryTerminated(event: QueryTerminatedEvent): Unit =
    handleOnQueryTerminated(event)
}

class ForeachBatchFn(val viewName: String)
    extends VoidFunction2[DataFrame, java.lang.Long]
    with Serializable {
  override def call(df: DataFrame, batchId: java.lang.Long): Unit = {
    val count = df.count()
    df.sparkSession
      .createDataFrame(Seq((batchId.toLong, count)))
      .createOrReplaceGlobalTempView(viewName)
  }
}
