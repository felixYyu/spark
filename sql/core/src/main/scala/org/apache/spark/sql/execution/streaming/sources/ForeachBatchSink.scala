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

package org.apache.spark.sql.execution.streaming.sources

import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.LogicalRDD
import org.apache.spark.sql.execution.streaming.Sink
import org.apache.spark.sql.streaming.DataStreamWriter

class ForeachBatchSink[T](batchWriter: (Dataset[T], Long) => Unit, encoder: ExpressionEncoder[T])
  extends Sink {

  override def addBatch(batchId: Long, data: DataFrame): Unit = {
    val rdd = data.queryExecution.toRdd
    val executedPlan = data.queryExecution.executedPlan
    val analyzedPlanWithoutMarkerNode = eliminateWriteMarkerNode(data.queryExecution.analyzed)
    // assertion on precondition
    assert(data.logicalPlan.output == analyzedPlanWithoutMarkerNode.output)
    val node = LogicalRDD(
      data.logicalPlan.output,
      rdd,
      Some(analyzedPlanWithoutMarkerNode),
      executedPlan.outputPartitioning,
      executedPlan.outputOrdering)(data.sparkSession)
    implicit val enc = encoder
    val ds = Dataset.ofRows(data.sparkSession, node).as[T]
    batchWriter(ds, batchId)
  }

  /**
   * ForEachBatchSink implementation reuses the logical plan of `data` which breaks the contract
   * of Sink.addBatch, which `data` should be just used to "collect" the output data.
   * We have to deal with eliminating marker node here which we do this in streaming specific
   * optimization rule.
   */
  private def eliminateWriteMarkerNode(plan: LogicalPlan): LogicalPlan = plan match {
    case node: WriteToMicroBatchDataSourceV1 => node.child
    case node => node
  }

  override def toString(): String = "ForeachBatchSink"
}

/**
 * Interface that is meant to be extended by Python classes via Py4J.
 * Py4J allows Python classes to implement Java interfaces so that the JVM can call back
 * Python objects. In this case, this allows the user-defined Python `foreachBatch` function
 * to be called from JVM when the query is active.
 * */
trait PythonForeachBatchFunction {
  /** Call the Python implementation of this function */
  def call(batchDF: DataFrame, batchId: Long): Unit
}

object PythonForeachBatchHelper {
  def callForeachBatch(dsw: DataStreamWriter[Row], pythonFunc: PythonForeachBatchFunction): Unit = {
    dsw.foreachBatch(pythonFunc.call _)
  }
}

