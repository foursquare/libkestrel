package com.twitter.libkestrel

import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import java.util.concurrent.atomic.AtomicInteger
import com.twitter.util._

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
class ConcurrentBlockingQueue[A <: AnyRef](
  maxItems: Long,
  fullPolicy: ConcurrentBlockingQueue.FullPolicy,
  timer: Timer
) {
  import ConcurrentBlockingQueue._

  /**
   * The actual queue of items.
   * We assume that normally there are more readers than writers, so the queue is normally empty.
   * But when nobody is waiting, we degenerate into a non-blocking queue, and this queue comes
   * into play.
   */
  private[this] val queue = new ConcurrentLinkedQueue[A]

  /**
   * A queue of readers waiting to retrieve an item.
   * `waiters` tracks the order for fairness, but `waiterSet` is the definitive set: a waiter may
   * be in the queue but not in the set, which just means that they had a timeout set, and gave up.
   */
  case class Waiter(timerTask: Option[TimerTask], promise: Promise[A])
  private[this] val waiters = new ConcurrentLinkedQueue[Waiter]
  private[this] val waiterSet = new ConcurrentHashMap[Promise[A], Waiter]

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
  final def get(): Future[A] = get(None)

  /**
   * Get the next item from the queue if it arrives before a timeout.
   */
  final def get(timeout: Duration): Future[A] = get(Some(timeout))

  private def get(timeout: Option[Duration]): Future[A] = {
    val promise = new Promise[A]
    val timerTask = timeout.map { t =>
      timer.schedule(t.fromNow) {
        if (waiterSet.remove(promise) ne null) {
          promise.setException(new TimeoutException(t.toString))
        }
      }
    }
    val waiter = Waiter(timerTask, promise)
    waiterSet.put(promise, waiter)
    waiters.add(waiter)
    if (! queue.isEmpty) handoff()
    promise
  }

  /**
   * This is the only code path allowed to remove an item from `queue` or `waiters`.
   */
  private def handoff() {
    if (triggerLock.getAndIncrement() == 0) {
      do {
        handoffOne()
      } while (triggerLock.decrementAndGet() > 0)
    }
  }

  private def handoffOne() {
    if (fullPolicy == FullPolicy.DropOldest) {
      // make sure we aren't over the max queue size.
      while (elementCount.get > maxItems) {
        queue.poll()
        elementCount.decrementAndGet()
      }
    }
    val item = queue.peek()
    if (item ne null) {
      var waiter = waiters.poll()
      while ((waiter ne null) && (waiterSet.remove(waiter.promise) eq null)) {
        waiter = waiters.poll()
      }
      if (waiter ne null) {
        waiter.timerTask.foreach { _.cancel() }
        waiter.promise.setValue(item)
        queue.poll()
        elementCount.decrementAndGet()
      }
    }
  }
}
