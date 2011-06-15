package com.twitter.gizzard.config

import com.twitter.util.Duration
import com.twitter.conversions.time._
import com.twitter.querulous.config.{Connection, QueryEvaluator}
import com.twitter.querulous.evaluator.QueryEvaluatorFactory

import com.twitter.gizzard
import com.twitter.gizzard.nameserver
import com.twitter.gizzard.shards


trait MappingFunction extends (() => Long => Long)

object ByteSwapper extends MappingFunction { def apply() = nameserver.ByteSwapper }
object Identity extends MappingFunction { def apply() = identity _ }
object Fnv1a64 extends MappingFunction { def apply() = nameserver.FnvHasher }
object Hash extends MappingFunction { def apply() = nameserver.FnvHasher }

trait Replica {
  def apply(): nameserver.Shard
}

trait Mysql extends Replica {
  def connection: Connection
  var queryEvaluator: QueryEvaluator = new QueryEvaluator

  def apply() = new nameserver.SqlShard(queryEvaluator()(connection))
}

object Memory extends Replica {
  def apply() = new nameserver.MemoryShard
}

trait NameServer {
  var mappingFunction: MappingFunction = Hash
  def replicas: Seq[Replica]

  def apply[T]() = {
    val replicaNodes  = replicas map { replica => shards.LeafRoutingNode(replica()) }

    val shardInfo     = new shards.ShardInfo("com.twitter.gizzard.nameserver.ReplicatingShard", "", "")
    val replicating   = new shards.ReplicatingShard(shardInfo, 0, replicaNodes)

    new nameserver.NameServer(replicating, mappingFunction())
  }
}
