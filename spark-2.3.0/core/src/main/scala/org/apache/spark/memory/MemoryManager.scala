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

// scalastyle:off
package org.apache.spark.memory

import javax.annotation.concurrent.GuardedBy

import org.apache.spark.SparkConf
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config._
import org.apache.spark.storage.BlockId
import org.apache.spark.storage.memory.MemoryStore
import org.apache.spark.unsafe.Platform
import org.apache.spark.unsafe.array.ByteArrayMethods
import org.apache.spark.unsafe.memory.MemoryAllocator

/**
 * An abstract memory manager that enforces how memory is shared
 * between execution and storage.
 *
 * In this context, execution memory refers to that used for
 * computation in shuffles, joins, sorts and aggregations, while
 * storage memory refers to that used for caching and propagating
 * internal data across the cluster. There exists one MemoryManager
 * per JVM.
 */
private[spark] abstract class MemoryManager(
    conf: SparkConf,
    numCores: Int,
    onHeapStorageMemory: Long,
    onHeapExecutionMemory: Long) extends Logging {

  // -- Methods related to memory allocation policies and bookkeeping ------------------------------

  @GuardedBy("this")
  protected val onHeapStorageMemoryPool = new StorageMemoryPool(this, MemoryMode.ON_HEAP)
  @GuardedBy("this")
  protected val offHeapStorageMemoryPool = new StorageMemoryPool(this, MemoryMode.OFF_HEAP)
  @GuardedBy("this")
  protected val onHeapExecutionMemoryPool = new ExecutionMemoryPool(this, MemoryMode.ON_HEAP)
  @GuardedBy("this")
  protected val offHeapExecutionMemoryPool = new ExecutionMemoryPool(this, MemoryMode.OFF_HEAP)

  /**
   * Jack Kolokasis (02/10/18)
   *
   * Implement the same bookkeping and memory allocation objects as as
   * it happens for the in memory allocations to the emulated
   * Persistent memory.
   */
  @GuardedBy("this")
  protected val pmemOffHeapStorageMemoryPool = new StorageMemoryPool(this, MemoryMode.PMEM_OFF_HEAP)
  @GuardedBy("this")
  protected val pmemOffHeapExecutionMemoryPool = new ExecutionMemoryPool(this, MemoryMode.PMEM_OFF_HEAP)

  onHeapStorageMemoryPool.incrementPoolSize(onHeapStorageMemory)
  onHeapExecutionMemoryPool.incrementPoolSize(onHeapExecutionMemory)

  protected[this] val maxOffHeapMemory = conf.get(MEMORY_OFFHEAP_SIZE)
  protected[this] val offHeapStorageMemory =
    (maxOffHeapMemory * conf.getDouble("spark.memory.storageFraction", 0.5)).toLong

  offHeapExecutionMemoryPool.incrementPoolSize(maxOffHeapMemory - offHeapStorageMemory)
  offHeapStorageMemoryPool.incrementPoolSize(offHeapStorageMemory)

  /**
   * Jack Kolokasis (03/10/18)
   * 
   * Calculate the max persistent offHeap Memory by read
   */
  protected[this] val maxPmemOffHeapMemory = conf.get(PMEM_OFFHEAP_SIZE)

  /**
   * Jack Kolokasis (03/10/18)
   * 
   * Calculate the persistent offHeap storage memory.
   */

  protected[this] val pmemOffHeapStorageMemory =
      (maxPmemOffHeapMemory * conf.getDouble("spark.memory.storageFraction", 0.5)).toLong

  /**
   * Jack Kolokasis (05/10/18)
   * 
   * Increment the pool size of the persistent off-Heap Execution
   * memoyry pool
   */
  pmemOffHeapExecutionMemoryPool.incrementPoolSize(maxPmemOffHeapMemory -
      pmemOffHeapStorageMemory)
  /**
   * Jack Kolokasis (05/10/18)
   * 
   * Increment the pool size of the persistent off-Heap Storage memory
   * pool
   */
  pmemOffHeapStorageMemoryPool.incrementPoolSize(pmemOffHeapStorageMemory)

  /**
   * Total available on heap memory for storage, in bytes. This amount
   * can vary over time, depending on the MemoryManager
   * implementation.  In this model, this is equivalent to the amount
   * of memory not occupied by execution.
   */
  def maxOnHeapStorageMemory: Long

  /**
   * Total available off heap memory for storage, in bytes. This
   * amount can vary over time, depending on the MemoryManager
   * implementation.
   */
  def maxOffHeapStorageMemory: Long

  /**
   * Jack Kolokasis
   *
   * Total available persistent off heap memory for storage, in bytes.
   * This amount can vary over time, depending on the MemoryManager
   * implementation.
   */
   def maxPmemOffHeapStorageMemory: Long

  /**
   * Set the [[MemoryStore]] used by this manager to evict cached
   * blocks.  This must be set after construction due to
   * initialization ordering constraints.
   */
  final def setMemoryStore(store: MemoryStore): Unit = synchronized {
    println("MemoryManager::setMemoryStore")
    onHeapStorageMemoryPool.setMemoryStore(store)
    offHeapStorageMemoryPool.setMemoryStore(store)
    /** Jack Kolokasis added */
    pmemOffHeapStorageMemoryPool.setMemoryStore(store)
  }

  // JK: Following methods need to specify its memory mode (Memory
  // Mode), this parameter determines whether to complete this
  // operation in heap or outside the heap
  /**
   * Acquire N bytes of memory to cache the given block, evicting
   * existing ones if necessary.
   *
   * @return whether all N bytes were successfully granted.
   */
  def acquireStorageMemory(blockId: BlockId, numBytes: Long, memoryMode: MemoryMode): Boolean

  /**
   * Acquire N bytes of memory to unroll the given block, evicting
   * existing ones if necessary.
   *
   * This extra method allows subclasses to differentiate behavior
   * between acquiring storage memory and acquiring unroll memory. For
   * instance, the memory management model in Spark
   * 1.5 and before places a limit on the amount of space that can be
   *   freed from unrolling.
   *
   * @return whether all N bytes were successfully granted.
   */
  def acquireUnrollMemory(blockId: BlockId, numBytes: Long, memoryMode: MemoryMode): Boolean

  /**
   * Try to acquire up to `numBytes` of execution memory for the
   * current task and return the number of bytes obtained, or 0 if
   * none can be allocated.
   *
   * This call may block until there is enough free memory in some
   * situations, to make sure each task has a chance to ramp up to at
   * least 1 / 2N of the total memory pool (where N is the # of active
   * tasks) before it is forced to spill. This can happen if the
   * number of tasks increase but an older task had a lot of memory
   * already.
   */
  private[memory]
  def acquireExecutionMemory(
      numBytes: Long,
      taskAttemptId: Long,
      memoryMode: MemoryMode): Long

  /**
   * Release numBytes of execution memory belonging to the given task.
   */
  private[memory]
  def releaseExecutionMemory(
      numBytes: Long,
      taskAttemptId: Long,
      memoryMode: MemoryMode): Unit = synchronized {
    println("MemoryManager::releaseExecutionMemory")
    memoryMode match {
      case MemoryMode.ON_HEAP => onHeapExecutionMemoryPool.releaseMemory(numBytes, taskAttemptId)
      case MemoryMode.OFF_HEAP => offHeapExecutionMemoryPool.releaseMemory(numBytes, taskAttemptId)
      case MemoryMode.PMEM_OFF_HEAP => pmemOffHeapExecutionMemoryPool.releaseMemory(numBytes, taskAttemptId)
    }
  }

  /**
   * Release all memory for the given task and mark it as inactive (e.g. when a task ends).
   *
   * @return the number of bytes freed.
   */
  private[memory] def releaseAllExecutionMemoryForTask(taskAttemptId: Long): Long = synchronized {
    println("MemoryManager::releaseAllExecutionMemoryForTask")
    onHeapExecutionMemoryPool.releaseAllMemoryForTask(taskAttemptId) +
      offHeapExecutionMemoryPool.releaseAllMemoryForTask(taskAttemptId) +
        pmemOffHeapExecutionMemoryPool.releaseAllMemoryForTask(taskAttemptId)
    }

  /**
   * Release N bytes of storage memory.
   */
  def releaseStorageMemory(numBytes: Long, memoryMode: MemoryMode): Unit = synchronized {
    println("MemoryManager::releaseStorageMemory")
    memoryMode match {
      case MemoryMode.ON_HEAP => onHeapStorageMemoryPool.releaseMemory(numBytes)
      case MemoryMode.OFF_HEAP => offHeapStorageMemoryPool.releaseMemory(numBytes)
      case MemoryMode.PMEM_OFF_HEAP => pmemOffHeapStorageMemoryPool.releaseMemory(numBytes)
    }
  }

  /**
   * Release all storage memory acquired.
   */
  final def releaseAllStorageMemory(): Unit = synchronized {
    println("MemoryManager::releaseAllStorageMemory")
    onHeapStorageMemoryPool.releaseAllMemory()
    offHeapStorageMemoryPool.releaseAllMemory()
    pmemOffHeapStorageMemoryPool.releaseAllMemory()
  }

  /**
   * Release N bytes of unroll memory.
   */
  final def releaseUnrollMemory(numBytes: Long, memoryMode: MemoryMode): Unit = synchronized {
    println("MemoryManager::releaseUnrollMemory")
    releaseStorageMemory(numBytes, memoryMode)
  }

  /**
   * Execution memory currently in use, in bytes.
   */
  final def executionMemoryUsed: Long = synchronized {
    println("MemoryManager::executionMemoryUsed")
    onHeapExecutionMemoryPool.memoryUsed + offHeapExecutionMemoryPool.memoryUsed +
      pmemOffHeapExecutionMemoryPool.memoryUsed

  }

  /**
   * Storage memory currently in use, in bytes.
   */
  final def storageMemoryUsed: Long = synchronized {
    println("MemoryManager::storageMemoryUsed")
    onHeapStorageMemoryPool.memoryUsed + offHeapStorageMemoryPool.memoryUsed + 
      pmemOffHeapStorageMemoryPool.memoryUsed
  }

  /**
   * Returns the execution memory consumption, in bytes, for the given task.
   */
  private[memory] def getExecutionMemoryUsageForTask(taskAttemptId: Long): Long = synchronized {
    println("MemoryManager::getExecutionMemoryUsageForTask")
    onHeapExecutionMemoryPool.getMemoryUsageForTask(taskAttemptId) +
      offHeapExecutionMemoryPool.getMemoryUsageForTask(taskAttemptId) + 
        pmemOffHeapExecutionMemoryPool.getMemoryUsageForTask(taskAttemptId)
  }

  // -- Fields related to Tungsten managed memory -------------------------------------------------

  /**
   * Tracks whether Tungsten memory will be allocated on the JVM heap or off-heap using
   * sun.misc.Unsafe.
   */
  final val tungstenMemoryMode: MemoryMode = {
    if (conf.get(MEMORY_OFFHEAP_ENABLED)) {
      require(conf.get(MEMORY_OFFHEAP_SIZE) > 0,
        "spark.memory.offHeap.size must be > 0 when spark.memory.offHeap.enabled == true")
      require(Platform.unaligned(),
        "No support for unaligned Unsafe. Set spark.memory.offHeap.enabled to false.")
      MemoryMode.OFF_HEAP

    } else if (conf.get(PMEM_OFFHEAP_ENABLED)) {
      require(conf.get(PMEM_OFFHEAP_SIZE) > 0,
        "spark.memory.offHeap.size must be > 0 when spark.memory.offHeap.enabled == true")
      Platform.nvmInitializeMemory("/mnt/pmemdir/executor", conf.get(PMEM_OFFHEAP_SIZE))
      MemoryMode.PMEM_OFF_HEAP
    } else {
      MemoryMode.ON_HEAP
    }
  }
  
  /**
   * The default page size, in bytes.
   *
   * If user didn't explicitly set "spark.buffer.pageSize", we figure out the default value
   * by looking at the number of cores available to the process, and the total amount of memory,
   * and then divide it by a factor of safety.
   */
  val pageSizeBytes: Long = {
    val minPageSize = 1L * 1024 * 1024   // 1MB
    val maxPageSize = 64L * minPageSize  // 64MB
    val cores = if (numCores > 0) numCores else Runtime.getRuntime.availableProcessors()
    // Because of rounding to next power of 2, we may have safetyFactor as 8 in worst case
    val safetyFactor = 16
    val maxTungstenMemory: Long = tungstenMemoryMode match {
      case MemoryMode.ON_HEAP => onHeapExecutionMemoryPool.poolSize
      case MemoryMode.OFF_HEAP => offHeapExecutionMemoryPool.poolSize
      case MemoryMode.PMEM_OFF_HEAP => pmemOffHeapExecutionMemoryPool.poolSize
    }
    val size = ByteArrayMethods.nextPowerOf2(maxTungstenMemory / cores / safetyFactor)
    val default = math.min(maxPageSize, math.max(minPageSize, size))
    conf.getSizeAsBytes("spark.buffer.pageSize", default)
  }

  /**
   * Allocates memory for use by Unsafe/Tungsten code.
   */
  private[memory] final val tungstenMemoryAllocator: MemoryAllocator = {
    println("MemoryManager::tungstenMemoryAllocator()")
    tungstenMemoryMode match {
      case MemoryMode.ON_HEAP => println("tungstenMemoryAllocator::ON_HEAP")
      case MemoryMode.OFF_HEAP => println("tungstenMemoryAllocator::OFF_HEAP")
      case MemoryMode.PMEM_OFF_HEAP => println("tungstenMemoryAllcator::PMEM_OFF_HEAP")
    }
    tungstenMemoryMode match {
      // JK: Create an object HeapMemoryAllocator
      case MemoryMode.ON_HEAP => MemoryAllocator.HEAP
      // JK: Create an object UnsafeMemoryAllocator
      case MemoryMode.OFF_HEAP => MemoryAllocator.UNSAFE
      // JK: Create an object on pmemUnsafeMemoryAllocator
      case MemoryMode.PMEM_OFF_HEAP => MemoryAllocator.NVM_UNSAFE
    }
  }
}
// scalastyle:on