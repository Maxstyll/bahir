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
package org.apache.bahir.cloudant.common

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration._
import scala.language.implicitConversions
import scala.util.{Failure, Success}

import com.google.gson.{GsonBuilder, JsonElement, JsonObject}
import scalaj.http.{Http, HttpRequest, HttpResponse}
import ExecutionContext.Implicits.global
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json._

import org.apache.bahir.cloudant.CloudantConfig

class JsonStoreDataAccess (config: CloudantConfig)  {
  lazy val logger: Logger = LoggerFactory.getLogger(getClass)
  implicit lazy val timeout: Long = config.requestTimeout
  private val gson = new GsonBuilder().create()

  def getMany(limit: Int)(implicit columns: Array[String] = null): Seq[String] = {
    if (limit == 0) {
      throw new CloudantException("Database " + config.getDbname +
        " schema sample size is 0!")
    }
    if (limit < -1) {
      throw new CloudantException("Database " + config.getDbname +
        " schema sample size is " + limit + "!")
    }
    // var r = this.getQueryResult[Seq[String]](config.getUrl(limit), processAll)
    var r = config.getUrl(limit)
    if (r.isEmpty) {
      // r = this.getQueryResult[Seq[String]](config.getUrl(limit, excludeDDoc = true),
      //  processAll)
      r = config.getUrl(limit, excludeDDoc = true)
    }
    if (r.isEmpty) {
      throw new CloudantException("Database " + config.getDbname +
        " doesn't have any non-design documents!")
    } else {
      // post-processing using previous processAll logic
      r.map(r => convert(r))
    }
  }

  def getIterator(skip: Int, limit: Int, url: String)
      (implicit columns: Array[String] = null,
      postData: String = null): Iterator[String] = {
    logger.info(s"Loading data from Cloudant using: $url , postData: $postData")

    val startTime = System.currentTimeMillis

    var rows = config.getSubSetUrl(url, skip, limit, postData != null)

    val finishTime = System.currentTimeMillis

    logger.info("Time for Cloudant _all_docs response: " + ((finishTime - startTime) / 1000))
    // this.getQueryResult[Iterator[String]](newUrl, processIterator)
    // rows.map(j => Json.parse(j.getAsString))
    if (config.viewPath == null && postData == null) {
      // filter design docs
      rows = rows.filter(r => FilterDocumentDDocs.filter(r))
    }
    val finish1Time = System.currentTimeMillis

    logger.info("Time for filter: " + ((finish1Time - finishTime) / 1000))

    rows.map(r => convertToString(r)).iterator
  }

  def getTotalRows(url: String, queryUsed: Boolean)
      (implicit postData: String = null): Int = {
      if (queryUsed) config.queryLimit // Query can not retrieve total row now.
      else {
        config.getTotal(url)
      }
  }

  private def processAll (result: String)
      (implicit columns: Array[String],
      postData: String = null) = {
    logger.debug(s"processAll:$result, columns:$columns")
    val jsonResult: JsValue = Json.parse(result)
    var rows = config.getRows(jsonResult, postData != null )
    if (config.viewName == null && postData == null) {
      // filter design docs
      rows = rows.filter(r => FilterDDocs.filter(r))
    }
    rows.map(r => convert(r))
  }

  private def processIterator (result: String)
    (implicit columns: Array[String],
      postData: String = null): Iterator[String] = {
    processAll(result).iterator
  }

  private def convert(rec: JsValue)(implicit columns: Array[String]): String = {
    if (columns == null) return Json.stringify(Json.toJson(rec))
    val m = new mutable.HashMap[String, JsValue]()
    for ( x <- columns) {
        val field = JsonUtil.getField(rec, x).getOrElse(JsNull)
        m.put(x, field)
    }
    val result = Json.stringify(Json.toJson(m.toMap))
    logger.debug(s"converted: $result")
    result
  }

  private def convertToString(rec: JsonObject)(implicit columns: Array[String]): String = {
    val startConvertTime = System.currentTimeMillis
    if (columns == null) return gson.toJson(rec)
    val m = new mutable.HashMap[String, JsonElement]()
    for ( x <- columns) {
      val field = rec.get(x)
      m.put(x, field)
    }
    // val result = Json.stringify(Json.toJson(m.toMap))
    val result = gson.toJson(m.toMap)
    // logger.debug(s"converted: $result")
    val finishConvertTime = System.currentTimeMillis
    logger.info("Time for convert: " + ((finishConvertTime - startConvertTime) / 1000))
    println("Time for convert: " + ((finishConvertTime - startConvertTime) / 1000)) // scalastyle:ignore
    result
  }

  def createDB(): Unit = {
    config.getClient.createDB(config.getDbname)
  }

  def getClRequest(url: String, postData: String = null,
                   httpMethod: String = null): HttpRequest = {
    val requestTimeout = config.requestTimeout.toInt
    config.username match {
      case null =>
        if (postData != null) {
          Http(url)
            .postData(postData)
            .timeout(connTimeoutMs = 1000, readTimeoutMs = requestTimeout)
            .header("Content-Type", "application/json")
            .header("User-Agent", "spark-cloudant")
        } else {
          if (httpMethod != null) {
            Http(url)
              .method(httpMethod)
              .timeout(connTimeoutMs = 1000, readTimeoutMs = requestTimeout)
              .header("User-Agent", "spark-cloudant")
          } else {
            Http(url)
              .timeout(connTimeoutMs = 1000, readTimeoutMs = requestTimeout)
              .header("User-Agent", "spark-cloudant")
          }
        }
      case _ =>
        if (postData != null) {
          Http(url)
            .postData(postData)
            .timeout(connTimeoutMs = 1000, readTimeoutMs = requestTimeout)
            .header("Content-Type", "application/json")
            .header("User-Agent", "spark-cloudant")
            .auth(config.username, config.password)
        } else {
          if (httpMethod != null) {
            Http(url)
              .method(httpMethod)
              .timeout(connTimeoutMs = 1000, readTimeoutMs = requestTimeout)
              .header("User-Agent", "spark-cloudant")
              .auth(config.username, config.password)
          } else {
            Http(url)
              .timeout(connTimeoutMs = 1000, readTimeoutMs = requestTimeout)
              .header("User-Agent", "spark-cloudant")
              .auth(config.username, config.password)
          }
        }
    }
  }


  def saveAll(rows: List[String]): Unit = {
    if (rows.isEmpty) return
    val bulkSize = config.bulkSize
    val bulks = rows.grouped(bulkSize).toList
    val totalBulks = bulks.size
    logger.debug(s"total records:${rows.size}=bulkSize:$bulkSize * totalBulks:$totalBulks")

    val futures = bulks.map( bulk => {
      val data = config.getBulkRows(bulk)
      val url = config.getBulkPostUrl.toString
      val clRequest: HttpRequest = getClRequest(url, data)
        Future {
          clRequest.execute()
        }
      }
    )
    // remaining - number of requests remained to succeed
    val remaining = new AtomicInteger(futures.length)
    val p = Promise[HttpResponse[String]]
    futures foreach {
      _ onComplete {
        case Success(clResponse: HttpResponse[String]) =>
          // find if there was error in saving at least one of docs
          val resBody: String = clResponse.body
          val isErr = (resBody contains config.getConflictErrStr) ||
            (resBody contains config.getForbiddenErrStr)
          if (!clResponse.isSuccess || isErr) {
            val e = new CloudantException("Save to database:" + config.getDbname +
                " failed with reason: " + clResponse.body)
            p.tryFailure(e)
          } else if (remaining.decrementAndGet() == 0) {
            // succeed the whole save operation if all requests success
            p.trySuccess(clResponse)
          }
        // if a least one save request fails - fail the whole save operation
        case Failure(e) =>
          p.tryFailure(e)
      }
    }

    val mainFtr = p.future
    mainFtr onSuccess {
      case clResponsesList =>
        logger.warn(s"Saved total ${rows.length} " +
          s"with bulkSize $bulkSize " +
          s"for database: ${config.getDbname}")
    }
    mainFtr onFailure  {
      case e =>
        throw new CloudantException("Save to database:" + config.getDbname +
          " failed with reason: " + e.getMessage)
    }
    Await.result(mainFtr, (config.requestTimeout * totalBulks).millis) // scalastyle:ignore
  }

}
