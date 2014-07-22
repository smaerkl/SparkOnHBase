package spark.hbase

import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.spark.rdd.RDD
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.client.HConnectionManager
import org.apache.spark.api.java.JavaPairRDD
import java.io.OutputStream
import org.apache.hadoop.hbase.client.HTable
import org.apache.hadoop.hbase.client.Scan
import org.apache.hadoop.hbase.client.Get
import java.util.ArrayList
import org.apache.hadoop.hbase.client.Result
import scala.reflect.ClassTag
import org.apache.hadoop.hbase.client.HConnection
import org.apache.hadoop.hbase.client.Put
import org.apache.hadoop.hbase.client.Increment
import org.apache.hadoop.hbase.client.Delete
import org.apache.spark.SparkContext
import org.apache.hadoop.hbase.mapreduce.TableInputFormat
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.hbase.mapreduce.TableMapper
import org.apache.hadoop.hbase.mapreduce.IdentityTableMapper
import org.apache.hadoop.hbase.protobuf.ProtobufUtil
import org.apache.hadoop.hbase.util.Base64
import org.apache.hadoop.hbase.mapreduce.MutationSerialization
import org.apache.hadoop.hbase.mapreduce.ResultSerialization
import org.apache.hadoop.hbase.mapreduce.KeyValueSerialization
import org.apache.spark.rdd.HadoopRDD
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.SerializableWritable
import java.util.HashMap
import org.apache.hadoop.hbase.protobuf.generated.MasterProtos
import org.apache.hadoop.hbase.protobuf.generated.MasterProtos
import java.util.concurrent.atomic.AtomicInteger
import org.apache.hadoop.hbase.HConstants
import java.util.concurrent.atomic.AtomicLong
import java.util.Timer
import java.util.TimerTask
import org.apache.hadoop.hbase.client.Mutation
import scala.collection.mutable.MutableList
import org.apache.spark.streaming.dstream.DStream

/**
 * HBaseContext is a façade of simple and complex HBase operations
 * like bulk put, get, increment, delete, and scan
 *
 * HBase Context will take the responsibilities to happen to
 * complexity of disseminating the configuration information
 * to the working and managing the life cycle of HConnections.
 *
 * First constructor:
 *  @param sc - active SparkContext
 *  @param broadcastedConf - This is a Broadcast object that holds a
 * serializable Configuration object
 *
 */
@serializable class HBaseContext(@transient sc: SparkContext,
  broadcastedConf: Broadcast[SerializableWritable[Configuration]]) {

  /**
   * Second constructor option:
   *  @param sc     active SparkContext
   *  @param config Configuration object to make connection to HBase
   */
  def this(@transient sc: SparkContext, @transient config: Configuration) {
    this(sc, sc.broadcast(new SerializableWritable(config)))
  }

  /**
   * A simple enrichment of the traditional Spark RDD foreachPartition.
   * This function differs from the original in that it offers the 
   * developer access to a already connected HConnection object
   * 
   * Note: Do not close the HConnection object.  All HConnection
   * management is handled outside this method
   * 
   * @param RDD[t]  Original RDD with data to iterate over
   * @param f       function to be given a iterator to iterate through
   *                the RDD values and a HConnection object to interact 
   *                with HBase 
   */
  def foreachPartition[T](rdd: RDD[T],
    f: (Iterator[T], HConnection) => Unit) = {
    rdd.foreachPartition(
      it => hbaseForeachPartition(broadcastedConf, it, f))
  }

  /**
   * A simple enrichment of the traditional Spark Streaming dStream foreach
   * This function differs from the original in that it offers the 
   * developer access to a already connected HConnection object
   * 
   * Note: Do not close the HConnection object.  All HConnection
   * management is handled outside this method
   * 
   * @param DStream[t]  Original DStream with data to iterate over
   * @param f           function to be given a iterator to iterate through
   *                    the DStream values and a HConnection object to 
   *                    interact with HBase 
   */
  def streamForeach[T](dstream: DStream[T],
    f: (Iterator[T], HConnection) => Unit) = {
    dstream.foreach((rdd, time) => {
      foreachPartition(rdd, f)
    })
  }

  /**
   * A simple enrichment of the traditional Spark RDD mapPartition.
   * This function differs from the original in that it offers the 
   * developer access to a already connected HConnection object
   * 
   * Note: Do not close the HConnection object.  All HConnection
   * management is handled outside this method
   * 
   * Note: Make sure to partition correctly to avoid memory issue when
   *       getting data from HBase
   * 
   * @param RDD[t]  Original RDD with data to iterate over
   * @param mp      function to be given a iterator to iterate through
   *                the RDD values and a HConnection object to interact 
   *                with HBase
   * @return        Returns a new RDD generated by the user definition
   *                function just like normal mapPartition
   */
  def mapPartition[T, U: ClassTag](rdd: RDD[T],
    mp: (Iterator[T], HConnection) => Iterator[U]): RDD[U] = {

    rdd.mapPartitions[U](it => hbaseMapPartition[T, U](broadcastedConf,
      it,
      mp), true)
  }

  /**
   * A simple enrichment of the traditional Spark Streaming DStream
   * mapPartition.
   * 
   * This function differs from the original in that it offers the 
   * developer access to a already connected HConnection object
   * 
   * Note: Do not close the HConnection object.  All HConnection
   * management is handled outside this method
   * 
   * Note: Make sure to partition correctly to avoid memory issue when
   *       getting data from HBase
   * 
   * @param DStream[t] Original DStream with data to iterate over
   * @param mp         function to be given a iterator to iterate through
   *                   the DStream values and a HConnection object to 
   *                   interact with HBase
   * @return           Returns a new DStream generated by the user 
   *                   definition function just like normal mapPartition
   */
  def streamMap[T, U: ClassTag](dstream: DStream[T],
    mp: (Iterator[T], HConnection) => Iterator[U]): DStream[U] = {

    dstream.mapPartitions(it => hbaseMapPartition[T, U](broadcastedConf,
      it,
      mp), true)
  }

  /**
   * A simple abstraction over the HBaseContext.foreachPartition method.
   * 
   * It allow addition support for a user to take RDD 
   * and generate puts and send them to HBase.  
   * The complexity of even the HConnection is 
   * removed from the developer
   * 
   * @param RDD[t]  Original RDD with data to iterate over
   * @tableName     The name of the table to put into 
   * @param f       function to convert a value in the RDD to a HBase Put
   * @autoFlush     if autoFlush should be turned on
   */
  def bulkPut[T](rdd: RDD[T], tableName: String, f: (T) => Put, autoFlush: Boolean) {

    rdd.foreachPartition(
      it => hbaseForeachPartition[T](
        broadcastedConf,
        it,
        (iterator, hConnection) => {
          val htable = hConnection.getTable(tableName)
          htable.setAutoFlush(autoFlush, true)
          iterator.foreach(T => htable.put(f(T)))
          htable.flushCommits()
          htable.close()
        }))
  }

  /**
   * A simple abstraction over the HBaseContext.streamMapPartition method.
   * 
   * It allow addition support for a user to take a DStream and 
   * generate puts and send them to HBase.  
   * 
   * The complexity of even the HConnection is 
   * removed from the developer
   * 
   * @param DStream[t] Original DStream with data to iterate over
   * @tableName        The name of the table to put into 
   * @param f          function to convert a value in the RDD to a HBase Put
   * @autoFlush        if autoFlush should be turned on
   */
  def streamBulkPut[T](dstream: DStream[T],
    tableName: String,
    f: (T) => Put,
    autoFlush: Boolean) = {
    dstream.foreach((rdd, time) => {
      bulkPut(rdd, tableName, f, autoFlush)
    })
  }

  /**
   * A simple abstraction over the HBaseContext.foreachPartition method.
   * 
   * It allow addition support for a user to take a RDD and 
   * generate increments and send them to HBase.  
   * 
   * The complexity of even the HConnection is 
   * removed from the developer
   * 
   * @param RDD[t]  Original RDD with data to iterate over
   * @tableName     The name of the table to increment to 
   * @param f       function to convert a value in the RDD to a 
   *                HBase Increments
   * @batchSize     The number of increments to batch before sending to HBase
   */
  def bulkIncrement[T](rdd: RDD[T], tableName:String, f:(T) => Increment, batchSize: Integer) {
    bulkMutation(rdd, tableName, f, batchSize)
  }
  
  /**
   * A simple abstraction over the HBaseContext.foreachPartition method.
   * 
   * It allow addition support for a user to take a RDD and generate delete
   * and send them to HBase.  The complexity of even the HConnection is 
   * removed from the developer
   * 
   * @param RDD[t]  Original RDD with data to iterate over
   * @tableName     The name of the table to delete from 
   * @param f       function to convert a value in the RDD to a 
   *                HBase Deletes
   * @batchSize     The number of delete to batch before sending to HBase
   */
  def bulkDelete[T](rdd: RDD[T], tableName:String, f:(T) => Delete, batchSize: Integer) {
    bulkMutation(rdd, tableName, f, batchSize)
  }
  
  /** 
   *  Under lining function to support all bulk mutations
   *  
   *  May be opened up if requested
   */
  private def bulkMutation[T](rdd: RDD[T], tableName: String, f: (T) => Mutation, batchSize: Integer) {
    rdd.foreachPartition(
      it => hbaseForeachPartition[T](
        broadcastedConf,
        it,
        (iterator, hConnection) => {
          val htable = hConnection.getTable(tableName)
          val mutationList = new ArrayList[Mutation]
          iterator.foreach(T => {
            mutationList.add(f(T))
            if (mutationList.size >= batchSize) {
              htable.batch(mutationList)
              mutationList.clear()
            }
          })
          if (mutationList.size() > 0) {
            htable.batch(mutationList)
            mutationList.clear()
          }
          htable.close()
        }))
  }

  /**
   * A simple abstraction over the HBaseContext.streamForeach method.
   * 
   * It allow addition support for a user to take a DStream and 
   * generate Increments and send them to HBase.  
   * 
   * The complexity of even the HConnection is 
   * removed from the developer
   * 
   * @param DStream[t] Original DStream with data to iterate over
   * @tableName        The name of the table to increments into 
   * @param f          function to convert a value in the RDD to a 
   *                   HBase Increments
   * @batchSize        The number of increments to batch before sending to HBase
   */
  def streamBulkIncrement[T](dstream: DStream[T],
    tableName: String,
    f: (T) => Increment,
    batchSize: Integer) = {
    streamBulkMutation(dstream, tableName, f, batchSize)
  }
  
  /**
   * A simple abstraction over the HBaseContext.streamForeach method.
   * 
   * It allow addition support for a user to take a DStream and 
   * generate Delete and send them to HBase.  
   * 
   * The complexity of even the HConnection is 
   * removed from the developer
   * 
   * @param DStream[t] Original DStream with data to iterate over
   * @tableName        The name of the table to delete from 
   * @param f          function to convert a value in the RDD to a 
   *                   HBase Delete
   * @batchSize        The number of deletes to batch before sending to HBase
   */
  def streamBulkDelete[T](dstream: DStream[T],
    tableName: String,
    f: (T) => Delete,
    batchSize: Integer) = {
    streamBulkMutation(dstream, tableName, f, batchSize)
  }
  
  /** 
   *  Under lining function to support all bulk streaming mutations
   *  
   *  May be opened up if requested
   */
  private def streamBulkMutation[T](dstream: DStream[T],
    tableName: String,
    f: (T) => Mutation,
    batchSize: Integer) = {
    dstream.foreach((rdd, time) => {
      bulkMutation(rdd, tableName, f, batchSize)
    })
  }


  /**
   * A simple abstraction over the HBaseContext.mapPartition method.
   * 
   * It allow addition support for a user to take a RDD and generates a
   * new RDD based on Gets and the results they bring back from HBase
   * 
   * @param RDD[t]     Original RDD with data to iterate over
   * @tableName        The name of the table to get from 
   * @param makeGet    function to convert a value in the RDD to a 
   *                   HBase Get
   * @param convertResult This will convert the HBase Result object to 
   *                   what ever the user wants to put in the resulting 
   *                   RDD
   * return            new RDD that is created by the Get to HBase
   */
  def bulkGet[T, U: ClassTag](tableName: String,
    batchSize: Integer,
    rdd: RDD[T],
    makeGet: (T) => Get,
    convertResult: (Result) => U): RDD[U] = {

    val getMapPartition = new GetMapPartition(tableName,
      batchSize,
      makeGet,
      convertResult)

    rdd.mapPartitions[U](it => hbaseMapPartition[T, U](broadcastedConf,
      it,
      getMapPartition.run), true)
  }

  /**
   * A simple abstraction over the HBaseContext.streamMap method.
   * 
   * It allow addition support for a user to take a DStream and 
   * generates a new DStream based on Gets and the results 
   * they bring back from HBase
   * 
   * @param DStream[t] Original DStream with data to iterate over
   * @param tableName        The name of the table to get from 
   * @param makeGet    function to convert a value in the DStream to a 
   *                   HBase Get
   * @param convertResult This will convert the HBase Result object to 
   *                   what ever the user wants to put in the resulting 
   *                   DStream
   * return            new DStream that is created by the Get to HBase    
   */
  def streamBulkGet[T, U: ClassTag](tableName: String,
      batchSize:Integer,
      dstream: DStream[T],
      makeGet: (T) => Get, 
      convertResult: (Result) => U): DStream[U] = {

    val getMapPartition = new GetMapPartition(tableName,
      batchSize,
      makeGet,
      convertResult)

    dstream.mapPartitions[U](it => hbaseMapPartition[T, U](broadcastedConf,
      it,
      getMapPartition.run), true)
  }

  /**
   * This function will use the native HBase TableInputFormat with the 
   * given scan object to generate a new RDD
   * 
   *  @param tableName the name of the table to scan
   *  @param scan      the HBase scan object to use to read data from HBase
   *  @param f         function to convert a Result object from HBase into 
   *                   what the user wants in the final generated RDD
   *  @return          new RDD with results from scan 
   */
  def hbaseRDD[U: ClassTag](tableName: String, scan: Scan, f: ((ImmutableBytesWritable, Result)) => U): RDD[U] = {

    var job: Job = new Job(broadcastedConf.value.value)

    TableMapReduceUtil.initTableMapperJob(tableName, scan, classOf[IdentityTableMapper], null, null, job)

    sc.newAPIHadoopRDD(job.getConfiguration(),
      classOf[TableInputFormat],
      classOf[ImmutableBytesWritable],
      classOf[Result]).map(f)
  }

  /**
   * A overloaded version of HBaseContext hbaseRDD that predefines the 
   * type of the outputing RDD
   * 
   *  @param tableName the name of the table to scan
   *  @param scan      the HBase scan object to use to read data from HBase
   *  @return New RDD with results from scan 
   * 
   */
  def hbaseRDD(tableName: String, scans: Scan): RDD[(Array[Byte], java.util.List[(Array[Byte], Array[Byte], Array[Byte])])] = {
    hbaseRDD[(Array[Byte], java.util.List[(Array[Byte], Array[Byte], Array[Byte])])](
      tableName,
      scans,
      (r: (ImmutableBytesWritable, Result)) => {
        val it = r._2.list().iterator()
        val list = new ArrayList[(Array[Byte], Array[Byte], Array[Byte])]()

        while (it.hasNext()) {
          val kv = it.next()
          list.add((kv.getFamily(), kv.getQualifier(), kv.getValue()))
        }

        (r._1.copyBytes(), list)
      })
  }
  
  /** 
   *  Under lining wrapper all foreach functions in HBaseContext
   *  
   */
  private def hbaseForeachPartition[T](configBroadcast: Broadcast[SerializableWritable[Configuration]],
    it: Iterator[T],
    f: (Iterator[T], HConnection) => Unit) = {

    System.out.println("hbaseForeachPartition - enter")
    val config = configBroadcast.value.value

    val hConnection = HConnectionStaticCache.getHConnection(config)
    try {
      f(it, hConnection)
    } finally {
      System.out.println("hbaseForeachPartition - leave")
      HConnectionStaticCache.finishWithHConnection(config, hConnection)
      
    }
  }

  /** 
   *  Under lining wrapper all mapPartition functions in HBaseContext
   *  
   */
  private def hbaseMapPartition[K, U](configBroadcast: Broadcast[SerializableWritable[Configuration]],
    it: Iterator[K],
    mp: (Iterator[K], HConnection) => Iterator[U]): Iterator[U] = {

    val config = configBroadcast.value.value

    val hConnection = HConnectionStaticCache.getHConnection(config)

    try {
      val res = mp(it, hConnection)
      res
    } finally {
      HConnectionStaticCache.finishWithHConnection(config, hConnection)
    }
  }
  
  /** 
   *  Under lining wrapper all get mapPartition functions in HBaseContext
   *  
   */
  @serializable private  class GetMapPartition[T, U: ClassTag](tableName: String, 
      batchSize: Integer,
      makeGet: (T) => Get,
      convertResult: (Result) => U) {
    
    def run(iterator: Iterator[T], hConnection: HConnection): Iterator[U] = {
      val htable = hConnection.getTable(tableName)

      val gets = new ArrayList[Get]()
      var res = List[U]()

      while (iterator.hasNext) {
        gets.add(makeGet(iterator.next))

        if (gets.size() == batchSize) {
          var results = htable.get(gets)
          res = res ++ results.map(convertResult)
          gets.clear()
        }
      }
      if (gets.size() > 0) {
        val results = htable.get(gets)
        res = res ++ results.map(convertResult)
        gets.clear()
      }
      htable.close()
      res.iterator
    }
  }
}