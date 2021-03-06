package com.twitter.finagle.memcached.integration

import _root_.java.io.ByteArrayOutputStream
import com.twitter.common.application.ShutdownRegistry.ShutdownRegistryImpl
import com.twitter.common.zookeeper.ServerSet.EndpointStatus
import com.twitter.common.zookeeper.testing.ZooKeeperTestServer
import com.twitter.common.zookeeper.{ServerSets, ZooKeeperClient, ZooKeeperUtils}
import com.twitter.concurrent.Spool
import com.twitter.concurrent.Spool.*::
import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.builder.{ClientBuilder, Cluster}
import com.twitter.finagle.memcached.{CachePoolConfig, CacheNode, CachePoolCluster}
import com.twitter.finagle.memcached.{Client, PartitionedClient}
import com.twitter.finagle.zookeeper.ZookeeperServerSetCluster
import com.twitter.io.Buf
import com.twitter.util.{FuturePool, Await, Duration, Future}
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfter, FunSuite, Outcome}
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.junit.JUnitRunner
import scala.collection.mutable

@RunWith(classOf[JUnitRunner])
class ClusterClientTest
  extends FunSuite
  with BeforeAndAfter
  with Eventually
  with IntegrationPatience {

  /**
   * Note: This integration test requires a real Memcached server to run.
   */
  var shutdownRegistry: ShutdownRegistryImpl = null
  var testServers: List[TestMemcachedServer] = List()

  var zkServerSetCluster: ZookeeperServerSetCluster = null
  var zookeeperClient: ZooKeeperClient = null
  val zkPath = "/cache/test/silly-cache"
  var zookeeperServer: ZooKeeperTestServer = null
  var dest: Name = null
  val pool = FuturePool.unboundedPool

  val TimeOut = 15.seconds
  def boundedWait[T](body: => T): T = Await.result(pool { body }, TimeOut)

  before {
    // start zookeeper server and create zookeeper client
    shutdownRegistry = new ShutdownRegistryImpl
    zookeeperServer = new ZooKeeperTestServer(0, shutdownRegistry)
    zookeeperServer.startNetwork()

    // connect to zookeeper server
    zookeeperClient = boundedWait(
      zookeeperServer.createClient(ZooKeeperClient.digestCredentials("user","pass"))
    )

    // create serverset
    val serverSet = boundedWait(
      ServerSets.create(zookeeperClient, ZooKeeperUtils.EVERYONE_READ_CREATOR_ALL, zkPath)
    )
    zkServerSetCluster = new ZookeeperServerSetCluster(serverSet)

    // start five memcached server and join the cluster
    Await.result(
      Future.collect(
        (0 to 4) map { _ =>
          TestMemcachedServer.start() match {
            case Some(server) =>
              testServers :+= server
              pool { zkServerSetCluster.join(server.address) }
            case None =>
              fail("could not start TestMemcachedServer")
          }
        }
      )
    , TimeOut)

    if (!testServers.isEmpty) {
      // set cache pool config node data
      val cachePoolConfig: CachePoolConfig = new CachePoolConfig(cachePoolSize = 5)
      val output: ByteArrayOutputStream = new ByteArrayOutputStream
      CachePoolConfig.jsonCodec.serialize(cachePoolConfig, output)
      boundedWait(zookeeperClient.get().setData(zkPath, output.toByteArray, -1))

      // a separate client which only does zk discovery for integration test
      zookeeperClient = zookeeperServer.createClient(ZooKeeperClient.digestCredentials("user","pass"))

      // destination of the test cache endpoints
      dest = Resolver.eval("twcache!localhost:" + zookeeperServer.getPort.toString + "!" + zkPath)
    }
  }

  after {
    // shutdown zookeeper server and client
    shutdownRegistry.execute()

    if (!testServers.isEmpty) {
      // shutdown memcached server
      testServers foreach { _.stop() }
      testServers = List()
    }
  }

  override def withFixture(test: NoArgTest): Outcome = {
    if (!testServers.isEmpty) test()
    else {
      info("Cannot start memcached. Skipping test...")
      cancel()
    }
  }

  if (!sys.props.contains("SKIP_FLAKY"))
    test("Cache specific cluster - add and remove") {

      // the cluster initially must have 5 members
      val myPool = initializePool(5)

      var additionalServers = List[EndpointStatus]()

      /***** start 5 more memcached servers and join the cluster ******/
      // cache pool should remain the same size at this moment
      intercept[com.twitter.util.TimeoutException] {
        Await.result(expectPoolStatus(myPool, currentSize = 5, expectedPoolSize = -1, expectedAdd = -1, expectedRem = -1) {
          additionalServers = addMoreServers(5)
        }.liftToTry, 2.seconds)()
      }

      // update config data node, which triggers the pool update
      // cache pool cluster should be updated
      try {
        Await.result(expectPoolStatus(myPool, currentSize = 5, expectedPoolSize = 10, expectedAdd = 5, expectedRem = 0) {
          updateCachePoolConfigData(10)
        }.liftToTry, 10.seconds)()
      }
      catch { case _: Exception => fail("it shouldn't thrown an exception") }

      /***** remove 2 servers from the zk serverset ******/
      // cache pool should remain the same size at this moment
      intercept[com.twitter.util.TimeoutException] {
        Await.result(expectPoolStatus(myPool, currentSize = 10, expectedPoolSize = -1, expectedAdd = -1, expectedRem = -1) {
          additionalServers(0).leave()
          additionalServers(1).leave()
        }.liftToTry, 2.seconds)()
      }

      // update config data node, which triggers the pool update
      // cache pool should be updated
      try {
        Await.result(expectPoolStatus(myPool, currentSize = 10, expectedPoolSize = 8, expectedAdd = 0, expectedRem = 2) {
          updateCachePoolConfigData(8)
        }.liftToTry, 10.seconds)()
      }
      catch { case _: Exception => fail("it shouldn't thrown an exception") }

      /***** remove 2 more then add 3 ******/
      // cache pool should remain the same size at this moment
      intercept[com.twitter.util.TimeoutException] {
        Await.result(expectPoolStatus(myPool, currentSize = 8, expectedPoolSize = -1, expectedAdd = -1, expectedRem = -1) {
          additionalServers(2).leave()
          additionalServers(3).leave()
          addMoreServers(3)
        }.liftToTry, 2.seconds)()
      }

      // update config data node, which triggers the pool update
      // cache pool should be updated
      try {
        Await.result(expectPoolStatus(myPool, currentSize = 8, expectedPoolSize = 9, expectedAdd = 3, expectedRem = 2) {
          updateCachePoolConfigData(9)
        }.liftToTry, 10.seconds)()
      }
      catch { case _: Exception => fail("it shouldn't thrown an exception") }
    }


  if (!Option(System.getProperty("SKIP_FLAKY")).isDefined)
    test("zk failures test") {
      // the cluster initially must have 5 members
      val myPool = initializePool(5)

      /***** fail the server here to verify the pool manager will re-establish ******/
      // cache pool cluster should remain the same
      intercept[com.twitter.util.TimeoutException] {
        Await.result(expectPoolStatus(myPool, currentSize = 5, expectedPoolSize = -1, expectedAdd = -1, expectedRem = -1) {
          zookeeperServer.expireClientSession(zookeeperClient)
          zookeeperServer.shutdownNetwork()
        }.liftToTry, 2.seconds)()
      }

      /***** start the server now ******/
      // cache pool cluster should remain the same
      intercept[com.twitter.util.TimeoutException] {
        Await.result(expectPoolStatus(myPool, currentSize = 5, expectedPoolSize = -1, expectedAdd = -1, expectedRem = -1) {
          zookeeperServer.startNetwork
          Thread.sleep(2000)
        }.liftToTry, 2.seconds)()
      }

      /***** start 5 more memcached servers and join the cluster ******/
      // update config data node, which triggers the pool update
      // cache pool cluster should still be able to see underlying pool changes
      try {
        Await.result(expectPoolStatus(myPool, currentSize = 5, expectedPoolSize = 10, expectedAdd = 5, expectedRem = 0) {
          addMoreServers(5)
          updateCachePoolConfigData(10)
        }.liftToTry, 10.seconds)()
      }
      catch { case _: Exception => fail("it shouldn't thrown an exception") }
    }

  if (!Option(System.getProperty("SKIP_FLAKY")).isDefined)
    test("using backup pools") {
      // shutdown the server before initializing our cache pool cluster
      zookeeperServer.shutdownNetwork()

      // the cache pool cluster should pickup backup pools
      // the underlying pool will continue trying to connect to zk
      val myPool = initializePool(2, Some(scala.collection.immutable.Set(
        new CacheNode("host1", 11211, 1),
        new CacheNode("host2", 11212, 1))))

      // bring the server back online
      // give it some time we should see the cache pool cluster pick up underlying pool
      try {
        Await.result(expectPoolStatus(myPool, currentSize = 2, expectedPoolSize = 5, expectedAdd = 5, expectedRem = 2) {
          zookeeperServer.startNetwork
        }.liftToTry, 10.seconds)()
      }
      catch { case _: Exception => fail("it shouldn't thrown an exception") }

      /***** start 5 more memcached servers and join the cluster ******/
      // update config data node, which triggers the pool update
      // cache pool cluster should still be able to see underlying pool changes
      try {
        Await.result(expectPoolStatus(myPool, currentSize = 5, expectedPoolSize = 10, expectedAdd = 5, expectedRem = 0) {
          addMoreServers(5)
          updateCachePoolConfigData(10)
        }.liftToTry, 10.seconds)()
      }
      catch { case _: Exception => fail("it shouldn't thrown an exception") }
    }

  def updateCachePoolConfigData(size: Int) {
    val cachePoolConfig: CachePoolConfig = new CachePoolConfig(cachePoolSize = size)
    val output = new ByteArrayOutputStream
    CachePoolConfig.jsonCodec.serialize(cachePoolConfig, output)
    zookeeperClient.get().setData(zkPath, output.toByteArray, -1)
  }

  // create temporary zk clients for additional cache servers since we will need to
  // de-register these services by expiring corresponding zk client session
  def addMoreServers(size: Int): List[EndpointStatus] = {
    List.fill(size) {
      val server = TestMemcachedServer.start()
      testServers :+= server.get
      zkServerSetCluster.joinServerSet(server.get.address)
    }
  }

  def initializePool(
    expectedSize: Int,
    backupPool: Option[scala.collection.immutable.Set[CacheNode]]=None,
    ignoreConfigData: Boolean = false
  ): Cluster[CacheNode] = {
    val myCachePool =
      if (! ignoreConfigData) CachePoolCluster.newZkCluster(zkPath, zookeeperClient, backupPool = backupPool)
      else CachePoolCluster.newUnmanagedZkCluster(zkPath, zookeeperClient)

    Await.result(myCachePool.ready, TimeOut) // wait until the pool is ready
    myCachePool.snap match {
      case (cachePool, changes) =>
        assert(cachePool.size == expectedSize)
    }
    myCachePool
  }

  /**
    * return a future which will be complete only if the cache pool changed AND the changes
    * meet all expected conditions after executing operation passed in
   *
   * @param currentSize expected current pool size
    * @param expectedPoolSize expected pool size after changes, use -1 to expect any size
    * @param expectedAdd expected number of add event happened, use -1 to expect any number
    * @param expectedRem expected number of rem event happened, use -1 to expect any number
    * @param ops operation to execute
    */
  def expectPoolStatus(
    myCachePool: Cluster[CacheNode],
    currentSize: Int,
    expectedPoolSize: Int,
    expectedAdd: Int,
    expectedRem: Int
  )(ops: => Unit): Future[Unit] = {
    var addSeen = 0
    var remSeen = 0
    var poolSeen = mutable.HashSet[CacheNode]()

    def expectMore(spoolChanges: Spool[Cluster.Change[CacheNode]]): Future[Unit] = {
      spoolChanges match {
        case change *:: tail =>
          change match {
            case Cluster.Add(node) =>
              addSeen += 1
              poolSeen.add(node)
            case Cluster.Rem(node) =>
              remSeen += 1
              poolSeen.remove(node)
          }
          if ((expectedAdd == -1 || addSeen == expectedAdd) &&
            (expectedRem == -1 || remSeen == expectedRem) &&
            (expectedPoolSize == -1 || poolSeen.size == expectedPoolSize)) Future.Done
          else tail flatMap expectMore
      }
    }

    myCachePool.snap match {
      case (cachePool, changes) =>
        assert(cachePool.size == currentSize)
        poolSeen ++= cachePool
        val retFuture = changes flatMap expectMore
        ops // invoke the function now
        retFuture
    }
  }

  def trackCacheShards(client: PartitionedClient) = mutable.Set.empty[Client] ++
  ((0 until 100).map { n => client.clientOf("foo"+n) })
}
