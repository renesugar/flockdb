/*
 * Copyright 2010 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.flockdb
package jobs.single

import com.twitter.logging.Logger
import com.twitter.util.Time
import com.twitter.gizzard.scheduler.{JsonJob, JsonJobParser}
import com.twitter.gizzard.shards._
import com.twitter.conversions.time._
import com.twitter.flockdb.conversions.Numeric._
import com.twitter.flockdb.shards.Shard
import com.twitter.flockdb.shards.LockingRoutingNode._


case class NodePair(sourceId: Long, destinationId: Long)

abstract class SingleJobParser extends JsonJobParser {
  def log = Logger.get

  def apply(attributes: Map[String, Any]): JsonJob = {
    val writeSuccesses = try {
      attributes.get("write_successes") map {
        _.asInstanceOf[Seq[Seq[String]]] map { case Seq(h, tp) => ShardId(h, tp) }
      } getOrElse Nil
    } catch {
      case e => {
        log.warning("Error parsing write successes. falling back to non-memoization", e)
        Nil
      }
    }

    val casted = attributes.asInstanceOf[Map[String, AnyVal]]
    createJob(
      casted("source_id").toLong,
      casted("graph_id").toInt,
      casted("destination_id").toLong,
      casted("position").toLong,
      Time.fromSeconds(casted("updated_at").toInt),
      writeSuccesses.toList
    )
  }

  protected def createJob(
    sourceId: Long,
    graphId: Int,
    destinationId: Long,
    position: Long,
    updatedAt: Time,
    writeSuccesses: List[ShardId]
  ): Single
}

class AddParser(forwardingManager: ForwardingManager, uuidGenerator: UuidGenerator) extends SingleJobParser {
  protected def createJob(sourceId: Long, graphId: Int, destinationId: Long, position: Long, updatedAt: Time, successes: List[ShardId]) = {
    new Add(sourceId, graphId, destinationId, position, updatedAt, forwardingManager, uuidGenerator, successes)
  }
}

class RemoveParser(forwardingManager: ForwardingManager, uuidGenerator: UuidGenerator) extends SingleJobParser {
  protected def createJob(sourceId: Long, graphId: Int, destinationId: Long, position: Long, updatedAt: Time, successes: List[ShardId]) = {
    new Remove(sourceId, graphId, destinationId, position, updatedAt, forwardingManager, uuidGenerator, successes)
  }
}

class ArchiveParser(forwardingManager: ForwardingManager, uuidGenerator: UuidGenerator) extends SingleJobParser {
  protected def createJob(sourceId: Long, graphId: Int, destinationId: Long, position: Long, updatedAt: Time, successes: List[ShardId]) = {
    new Archive(sourceId, graphId, destinationId, position, updatedAt, forwardingManager, uuidGenerator, successes)
  }
}

class NegateParser(forwardingManager: ForwardingManager, uuidGenerator: UuidGenerator) extends SingleJobParser {
  protected def createJob(sourceId: Long, graphId: Int, destinationId: Long, position: Long, updatedAt: Time, successes: List[ShardId]) = {
    new Negate(sourceId, graphId, destinationId, position, updatedAt, forwardingManager, uuidGenerator, successes)
  }
}

abstract class Single(
  sourceId: Long,
  graphId: Int,
  destinationId: Long,
  position: Long,
  updatedAt: Time,
  forwardingManager: ForwardingManager,
  uuidGenerator: UuidGenerator)
extends JsonJob {

  def successes: List[ShardId]
  def successes_=(l: List[ShardId])

  def toMap = {
    val base =  Map(
      "source_id" -> sourceId,
      "graph_id" -> graphId,
      "destination_id" -> destinationId,
      "position" -> position,
      "updated_at" -> updatedAt.inSeconds
    )

    if (successes.isEmpty) {
      base
    } else {
      base + ("write_successes" -> (successes map { case ShardId(h, tp) => Seq(h, tp) }))
    }
  }

  def apply() = {
    val forward  = forwardingManager.findNode(sourceId, graphId, Direction.Forward)
    val backward = forwardingManager.findNode(destinationId, graphId, Direction.Backward)
    val uuid     = uuidGenerator(position)

    forward.optimistically(sourceId) { left =>
      backward.optimistically(destinationId) { right =>
        write(forward.write, backward.write, uuid, left max right max preferredState)
      }
    }

  }

  def writeToShard(shard: NodeSet[Shard], sourceId: Long, destinationId: Long, uuid: Long, state: State) = {
    try {
      state match {
        case State.Normal =>
          shard.foreach { _.add(sourceId, destinationId, uuid, updatedAt) }
        case State.Removed =>
          shard.foreach { _.remove(sourceId, destinationId, uuid, updatedAt) }
        case State.Archived =>
          shard.foreach { _.archive(sourceId, destinationId, uuid, updatedAt) }
        case State.Negative =>
          shard.foreach { _.negate(sourceId, destinationId, uuid, updatedAt) }
      }

      None
    } catch {
      case e => Some(e)
    }
  }

  def write(forward: NodeSet[Shard], backward: NodeSet[Shard], uuid: Long, state: State) {
    val forwardErr  = writeToShard(forward, sourceId, destinationId, uuid, state)
    val backwardErr = writeToShard(backward, destinationId, sourceId, uuid, state)

    // just eat ShardBlackHoleExceptions for either way, but throw any other
    List(forwardErr, backwardErr).flatMap(_.toList).foreach {
      case e: ShardBlackHoleException => ()
      case e => throw e
    }
  }

  protected def preferredState: State
}

case class Add(
  sourceId: Long,
  graphId: Int,
  destinationId: Long,
  position: Long,
  updatedAt: Time,
  forwardingManager: ForwardingManager,
  uuidGenerator: UuidGenerator,
  var successes: List[ShardId] = Nil)
extends Single(sourceId, graphId, destinationId, position, updatedAt, forwardingManager, uuidGenerator) {
  def preferredState = State.Normal
}

case class Remove(
  sourceId: Long,
  graphId: Int,
  destinationId: Long,
  position: Long,
  updatedAt: Time,
  forwardingManager: ForwardingManager,
  uuidGenerator: UuidGenerator,
  var successes: List[ShardId] = Nil)
extends Single(sourceId, graphId, destinationId, position, updatedAt, forwardingManager, uuidGenerator) {
  def preferredState = State.Removed
}

case class Archive(
  sourceId: Long,
  graphId: Int,
  destinationId: Long,
  position: Long,
  updatedAt: Time,
  forwardingManager: ForwardingManager,
  uuidGenerator: UuidGenerator,
  var successes: List[ShardId] = Nil)
extends Single(sourceId, graphId, destinationId, position, updatedAt, forwardingManager, uuidGenerator) {
  def preferredState = State.Archived
}

case class Negate(
  sourceId: Long,
  graphId: Int,
  destinationId: Long,
  position: Long,
  updatedAt: Time,
  forwardingManager: ForwardingManager,
  uuidGenerator: UuidGenerator,
  var successes: List[ShardId] = Nil)
extends Single(sourceId, graphId, destinationId, position, updatedAt, forwardingManager, uuidGenerator) {
  def preferredState = State.Negative
}
