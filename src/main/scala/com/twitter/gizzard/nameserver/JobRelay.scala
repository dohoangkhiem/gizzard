package com.twitter.gizzard.nameserver

import java.util.{LinkedList => JLinkedList}
import java.nio.ByteBuffer
import java.net.InetSocketAddress
import org.apache.thrift.protocol.TBinaryProtocol
import com.twitter.conversions.time._
import com.twitter.util.Duration
import java.util.logging.Logger
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.thrift.ThriftClientFramedCodec
import com.twitter.finagle.stats.OstrichStatsReceiver
import com.twitter.gizzard.scheduler.JsonJob
import com.twitter.gizzard.thrift.JobInjector
import com.twitter.gizzard.thrift.{Job => ThriftJob}


class ClusterBlockedException(cluster: String, cause: Throwable)
extends Exception("Job replication to cluster '" + cluster + "' is blocked.", cause) {
  def this(cluster: String) = this(cluster, null)
}

class JobRelayFactory(
  priority: Int,
  timeout: Duration,
  requestTimeout: Duration,
  retries: Int)
extends (Map[String, Seq[Host]] => JobRelay) {

  def this(priority: Int, timeout: Duration) = this(priority, timeout, timeout, 0)

  def apply(hostMap: Map[String, Seq[Host]]) =
    new JobRelay(hostMap, priority, timeout, requestTimeout, retries)
}

class JobRelay(
  hostMap: Map[String, Seq[Host]],
  priority: Int,
  timeout: Duration,
  requestTimeout: Duration,
  retries: Int)
extends (String => JobRelayCluster) {

  private val clients = hostMap.flatMap { case (c, hs) =>
    var blocked = false
    val onlineHosts = hs.filter(_.status match {
      case HostStatus.Normal     => true
      case HostStatus.Blocked    => { blocked = true; false }
      case HostStatus.Blackholed => false
    })

    if (onlineHosts.isEmpty) {
      if (blocked) Map(c -> new BlockedJobRelayCluster(c)) else Map[String, JobRelayCluster]()
    } else {
      Map(c -> new JobRelayCluster(onlineHosts, priority, timeout, requestTimeout, retries))
    }
  }

  val clusters = clients.keySet

  def apply(cluster: String) = clients.getOrElse(cluster, NullJobRelayCluster)

  def close() {
    clients.foreach { case (cluster, client) => client.close() }
  }
}

class JobRelayCluster(
  hosts: Seq[Host],
  priority: Int,
  timeout: Duration,
  requestTimeout: Duration,
  retries: Int)
extends (Iterable[Array[Byte]] => Unit) {
  val service = ClientBuilder()
      .codec(ThriftClientFramedCodec())
      .hosts(hosts.map { h => new InetSocketAddress(h.hostname, h.port) })
      .hostConnectionLimit(2)
      .retries(retries)
      .tcpConnectTimeout(timeout)
      .failureAccrualParams((10, 1.second))
      .requestTimeout(requestTimeout)
      .timeout(timeout)
      .name("JobManagerClient")
      .reportTo(new OstrichStatsReceiver)
      .build()
  val client = new JobInjector.ServiceToClient(service, new TBinaryProtocol.Factory())

  def apply(jobs: Iterable[Array[Byte]]) {
    val jobList = new JLinkedList[ThriftJob]()

    jobs.foreach { j =>
      val tj = new ThriftJob(priority, ByteBuffer.wrap(j))
      tj.setIs_replicated(true)
      jobList.add(tj)
    }

    client.inject_jobs(jobList)()
  }

  def close() {
    service.release()
  }
}

object NullJobRelayFactory extends JobRelayFactory(0, 0.seconds) {
  override def apply(h: Map[String, Seq[Host]]) = NullJobRelay
}

object NullJobRelay extends JobRelay(Map(), 0, 0.seconds, 0.seconds, 0)

object NullJobRelayCluster extends JobRelayCluster(Seq(), 0, 0.seconds, 0.seconds, 0) {
  override val client = null
  override def apply(jobs: Iterable[Array[Byte]]) = ()
  override def close() { }
}

class BlockedJobRelayCluster(cluster: String) extends JobRelayCluster(Seq(), 0, 0.seconds, 0.seconds, 0) {
  override val client = null
  override def apply(jobs: Iterable[Array[Byte]]) { throw new ClusterBlockedException(cluster) }
  override def close() { }
}
