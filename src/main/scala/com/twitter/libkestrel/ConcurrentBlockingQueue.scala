package com.twitter.libkestrel

import com.twitter.util._
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.JavaConverters._

object ConcurrentBlockingQueue {
  /** What to do when the queue is full and a `put` is attempted (for the constructor). */
  abstract sealed class FullPolicy

  object FullPolicy {
    /** When the queue is full, a `put` attempt returns `false`. */
    case object RefusePuts extends FullPolicy

    /** When the queue is full, a `put` attempt will throw away the head item. */
    case object DropOldest extends FullPolicy
  }

  /**
   * Make a queue with no effective size limit.
   */
  def apply[A <: AnyRef](implicit timer: Timer) = {
    new ConcurrentBlockingQueue[A](Long.MaxValue, FullPolicy.RefusePuts, timer)
  }

  /**
   * Make a queue with a fixed maximum item count and a policy for what to do when the queue is
   * full and a `put` is attempted.
   */
  def apply[A <: AnyRef](maxItems: Long, fullPolicy: FullPolicy)(implicit timer: Timer) = {
    new ConcurrentBlockingQueue[A](maxItems, fullPolicy, timer)
  }
}

/**
 * A ConcurrentLinkedQueue backed by a queue of waiters. FIXME
 *
 * @param maxItems maximum allowed size of the queue (use `Long.MaxValue` for infinite size)
 * @param fullPolicy what to do when the queue is full and a `put` is attempted
 */
final class ConcurrentBlockingQueue[A <: AnyRef](
  maxItems: Long,
  fullPolicy: ConcurrentBlockingQueue.FullPolicy,
  timer: Timer
) extends BlockingQueue[A] {
  import ConcurrentBlockingQueue._

  /**
   * The actual queue of items.
   * We assume that normally there are more readers than writers, so the queue is normally empty.
   * But when nobody is waiting, we degenerate into a non-blocking queue, and this queue comes
   * into play.
   */
  private[this] val queue = new ConcurrentLinkedQueue[A]

  /**
   * A queue of readers, some waiting with a timeout, others polling.
   * `consumers` tracks the order for fairness, but `waiterSet` and `pollerSet` are
   * the definitive sets: a waiter/poller may be the queue, but not in the set, which
   * just means that they had a timeout set and gave up or were rejected due to an
   * empty queue.
   */
  abstract sealed class Consumer {
    def promise: Promise[Option[A]]
    def apply(item: A): Boolean
  }
  private[this] val consumers = new ConcurrentLinkedQueue[Consumer]

  /**
   * A queue of readers waiting to retrieve an item. See `consumers`.
   */
  case class Waiter(promise: Promise[Option[A]], timerTask: Option[TimerTask]) extends Consumer {
    def apply(item: A) = {
      timerTask.foreach { _.cancel() }
      promise.setValue(Some(item))
      true
    }
  }
  private[this] val waiterSet = new ConcurrentHashMap[Promise[Option[A]], Promise[Option[A]]]

  /**
   * A queue of pollers just checking in to see if anything is immediately available.
   * See `consumers`.
   */
  case class Poller(promise: Promise[Option[A]], predicate: A => Boolean) extends Consumer {
    def apply(item: A) = {
      if (predicate(item)) {
        promise.setValue(Some(item))
        true
      } else {
        promise.setValue(None)
        false
      }
    }
  }
  private[this] val truth: A => Boolean = { _ => true }
  private[this] val pollerSet = new ConcurrentHashMap[Promise[Option[A]], Promise[Option[A]]]

  /**
   * An estimate of the queue size, tracked for each put/get.
   */
  private[this] val elementCount = new AtomicInteger(0)

  /**
   * Sequential lock used to serialize access to handoffOne().
   */
  private[this] val triggerLock = new AtomicInteger(0)

  /**
   * Inserts the specified element into this queue if it is possible to do so immediately without
   * violating capacity restrictions, returning `true` upon success and `false` if no space is
   * currently available.
   */
  def put(item: A): Boolean = {
    if (elementCount.get >= maxItems && fullPolicy == FullPolicy.RefusePuts) {
      false
    } else {
      queue.add(item)
      elementCount.incrementAndGet()
      handoff()
      true
    }
  }

  /**
   * Return the size of the queue as it was at some (recent) moment in time.
   */
  def size: Int = elementCount.get()

  /**
   * Get the next item from the queue, waiting forever if necessary.
   */
  def get(): Future[Option[A]] = get(None)

  /**
   * Get the next item from the queue if it arrives before a timeout.
   */
  def get(deadline: Time): Future[Option[A]] = get(Some(deadline))

  /**
   * Get the next item from the queue if one is immediately available.
   */
  def poll(): Option[A] = pollIf(truth)

  /**
   * Get the next item from the queue if it satisfies a predicate.
   */
  def pollIf(predicate: A => Boolean): Option[A] = {
    if (queue.isEmpty) {
      None
    } else {
      val promise = new Promise[Option[A]]
      pollerSet.put(promise, promise)
      consumers.add(Poller(promise, predicate))
      handoff()
      promise()
    }
  }

  private def get(deadline: Option[Time]): Future[Option[A]] = {
    val promise = new Promise[Option[A]]
    waiterSet.put(promise, promise)
    val timerTask = deadline.map { d =>
      timer.schedule(d) {
        if (waiterSet.remove(promise) ne null) {
          promise.setValue(None)
        }
      }
    }
    consumers.add(Waiter(promise, timerTask))
    if (!queue.isEmpty) handoff()
    promise
  }

  /**
   * This is the only code path allowed to remove an item from `queue` or `consumers`.
   */
  private[this] def handoff() {
    if (triggerLock.getAndIncrement() == 0) {
      do {
        handoffOne()
      } while (triggerLock.decrementAndGet() > 0)
    }
  }

  private[this] def handoffOne() {
    if (fullPolicy == FullPolicy.DropOldest) {
      // make sure we aren't over the max queue size.
      while (elementCount.get > maxItems) {
        // FIXME: increment counter about discarded?
        // offer discarded item
        queue.poll()
        elementCount.decrementAndGet()
      }
    }

    val item = queue.peek()
    if (item ne null) {
      var consumer: Consumer = null
      var invalid = true
      do {
        consumer = consumers.poll()
        invalid = consumer match {
          case null => false
          case Waiter(promise, _) => waiterSet.remove(promise) eq null
          case Poller(promise, _) => pollerSet.remove(promise) eq null
        }
      } while (invalid)

      if ((consumer ne null) && consumer(item)) {
        queue.poll()
        if (elementCount.decrementAndGet() == 0) {
          dumpPollerSet
        }
      }
    } else {
      // empty -- dump outstanding pollers
      dumpPollerSet
    }
  }

  private[this] def dumpPollerSet = {
    pollerSet.keySet.asScala.toArray.foreach { poller =>
      poller.setValue(None)
      pollerSet.remove(poller)
    }
  }

  def toDebug: String = {
    "<ConcurrentBlockingQueue size=%d waiters=%d/%d/%d>".format(elementCount.get, consumers.size, waiterSet.size, pollerSet.size)
  }
}
