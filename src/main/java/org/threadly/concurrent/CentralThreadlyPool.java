package org.threadly.concurrent;

import java.util.concurrent.atomic.LongAdder;

import org.threadly.concurrent.wrapper.PrioritySchedulerDefaultPriorityWrapper;
import org.threadly.concurrent.wrapper.limiter.SchedulerServiceLimiter;
import org.threadly.concurrent.wrapper.limiter.SingleThreadSchedulerSubPool;
import org.threadly.util.ArgumentVerifier;

/**
 * Threadly's centrally provided pool manager.  This class is designed to avoid needing to 
 * manage the thread pool lifecycle throughout your application.  Instead of needing to think about 
 * how to manage a pools lifecycle you can instead just describe the needs of the tasks to be 
 * submitted.
 * <p>
 * Internally this will delegate to a central or otherwise specific pool for your needs, while 
 * minimizing thread creation / churn as much as possible.  In addition the returned pools do not 
 * need to be shutdown, but instead you can allow to be garbage collected as you are done with 
 * them.  There is no need to be concerned about allowing a returned pool to be garbage collected 
 * before any submitted / scheduled / recurring tasks have completed.
 * <p>
 * Most users will find themselves sticking to the simple pools this provides:
 * <ul>
 * <li>{@link #computationPool()} for doing CPU bound computational tasks
 * <li>{@link #lowPriorityPool(boolean)} for doing low priority maintenance tasks
 * <li>{@link #singleThreadPool()} as a way to gain access to an efficient priority respected single thread pool
 * <li>{@link #threadPool(int)} and {@link #threadPool(TaskPriority, int)} to have a multi-threaded pool
 * </ul>
 * <p>
 * More advanced users can attempt to further reduce thread chun by adding general purpose threads 
 * with {@link #increaseGenericThreads(int)}.  You can then use 
 * {@link #singleThreadPool(boolean)} with {@code false} to just depend on these general processing 
 * threads.  And in addition {@link #rangedThreadCountPool(int, int)} and 
 * {@link #rangedThreadCountPool(TaskPriority, int, int)} in order to specify how when guaranteed 
 * threads need to be provided, and how much of the general processing threads the pool can take 
 * advantage of.
 * <p>
 * Stats (like {@link SchedulerService#getActiveTaskCount()} and 
 * {@link SchedulerService#getQueuedTaskCount()}, etc) from provided pools will always be 
 * representative of the entire central pool rather than just relative to the returned pool.
 * 
 * @since 5.7
 */
public class CentralThreadlyPool {
  protected static final int LOW_PRIORITY_MAX_WAIT_IN_MS = 1000;
  protected static final PoolResizeUpdater POOL_SIZE_UPDATER;
  protected static final PriorityScheduler MASTER_SCHEDULER;
  protected static final PrioritySchedulerService LOW_PRIORITY_MASTER_SCHEDULER;
  protected static final PrioritySchedulerService STARVABLE_PRIORITY_MASTER_SCHEDULER;
  protected static final SchedulerService COMPUTATION_POOL;
  protected static final SchedulerService LOW_PRIORITY_POOL;
  protected static final PrioritySchedulerService SINGLE_THREADED_LOW_PRIORITY_POOL;
  private static volatile int genericThreadCount;
  
  static {
    int cpuCount = Runtime.getRuntime().availableProcessors();
    genericThreadCount = 1; // must have at least one
    MASTER_SCHEDULER = // start with computation + 1 for interior management tasks and + 1 for shared use
        new PriorityScheduler(cpuCount + genericThreadCount + 1, 
                              TaskPriority.High, LOW_PRIORITY_MAX_WAIT_IN_MS, 
                              new ConfigurableThreadFactory("ThreadlyCentralPool-", false, 
                                                            true, Thread.NORM_PRIORITY, null, null));
    LOW_PRIORITY_MASTER_SCHEDULER = 
        new PrioritySchedulerDefaultPriorityWrapper(MASTER_SCHEDULER, TaskPriority.Low);
    STARVABLE_PRIORITY_MASTER_SCHEDULER = 
        new PrioritySchedulerDefaultPriorityWrapper(MASTER_SCHEDULER, TaskPriority.Starvable);
    
    POOL_SIZE_UPDATER = new PoolResizeUpdater(LOW_PRIORITY_MASTER_SCHEDULER);
    
    COMPUTATION_POOL = new SchedulerServiceLimiter(MASTER_SCHEDULER, cpuCount);
    LOW_PRIORITY_POOL = new DynamicGenericThreadLimiter(TaskPriority.Low, 0, -1);
    SINGLE_THREADED_LOW_PRIORITY_POOL = 
        new SingleThreadSubPool(TaskPriority.Low, TaskPriority.Low, false);
  }
  
  /**
   * Increase available threads threads that can be shared across pools.
   * 
   * @param count A positive number of threads to make available to the central pool
   */
  public static void increaseGenericThreads(int count) {
    ArgumentVerifier.assertGreaterThanZero(count, "count");
    
    synchronized (CentralThreadlyPool.class) {
      POOL_SIZE_UPDATER.adjustPoolSize(count);
      genericThreadCount += count;
    }
  }
  
  /**
   * This reports the number of threads currently available for processing across all pools where 
   * the max thread count is {@code >} the guaranteed thread count.
   * 
   * @return The number of threads currently available for general processing work
   */
  public static int getGenericThreadCount() {
    return genericThreadCount;
  }

  /**
   * Thread pool well suited for running CPU intensive computations on the tasks thread.
   * 
   * @return Pool for CPU bound tasks
   */
  public static SchedulerService computationPool() {
    return COMPUTATION_POOL;
  }
  
  /**
   * Low priority pool for scheduling cleanup or otherwise tasks which could be significantly 
   * delayed.  If not single threaded this pool will execute only on any general processing threads 
   * which are available.  By default there is only one, but it can be increased by invoking 
   * {@link #increaseGenericThreads(int)}.
   * 
   * @param singleThreaded {@code true} indicates that being blocked by other low priority tasks is not a concern
   * @return Pool for running or scheduling out low priority tasks
   */
  public static SchedulerService lowPriorityPool(boolean singleThreaded) {
    if (singleThreaded) {
      return SINGLE_THREADED_LOW_PRIORITY_POOL;
    } else {
      return LOW_PRIORITY_POOL;
    }
  }

  /**
   * Return a single threaded pool.  This can be useful for submitting tasks on where you don't 
   * want to worry about any concurrency or shared memory issues.  If you want a single threaded 
   * pool which is forced to use the already established pool limits, consider using 
   * {@link #singleThreadPool(boolean)} with {@code false} to ensure pool churn is reduced.
   * 
   * @return Single threaded pool for running or scheduling tasks on
   */
  public static PrioritySchedulerService singleThreadPool() {
    return singleThreadPool(true);
  }
  
  /**
   * Return a single threaded pool.  This can be useful for submitting tasks on where you don't 
   * want to worry about any concurrency or shared memory issues.
   * 
   * @param threadGuaranteed {@code true} indicates that the pool manager needs to expand if necessary
   * @return Single threaded pool for running or scheduling tasks on
   */
  public static PrioritySchedulerService singleThreadPool(boolean threadGuaranteed) {
    return new SingleThreadSubPool(TaskPriority.High, TaskPriority.High, threadGuaranteed);
  }
  
  /**
   * Requests a pool with a given size.  These threads are guaranteed to be available, but 
   * general processing threads will not be available to any pools returned by this.  If you want 
   * to be able to use part of the shared general processing threads use 
   * {@link #rangedThreadCountPool(int, int)} with either a higher or negative value for 
   * {@code maxThreads}.
   * 
   * @param threadCount The number of threads that will be available to tasks submitted on the returned pool
   * @return A pool with the requested threads available for task scheduling or execution
   */
  public static SchedulerService threadPool(int threadCount) {
    return threadPool(TaskPriority.High, threadCount);
  }

  /**
   * Requests a pool with a given size.  These threads are guaranteed to be available, but 
   * general processing threads will not be available to any pools returned by this.  If you want 
   * to be able to use part of the shared general processing threads use 
   * {@link #rangedThreadCountPool(TaskPriority, int, int)} with either a higher or negative value 
   * for {@code maxThreads}.
   * 
   * @param priority Priority for tasks submitted on returned scheduler service
   * @param threadCount The number of threads that will be available to tasks submitted on the returned pool
   * @return A pool with the requested threads available for task scheduling or execution
   */
  public static SchedulerService threadPool(TaskPriority priority, int threadCount) {
    return rangedThreadCountPool(priority, threadCount, threadCount);
  }
  
  /**
   * Requests a pool with a given range of threads.  Minimum threads are guaranteed to be available 
   * to tasks submitted to the returned threads.  In addition to those minimum threads tasks 
   * submitted may run on "general processing" threads (starts at {@code 1} but can be increased by 
   * invoking {@link #increaseGenericThreads(int)}).  If {@code maxThreads} is 
   * {@code > 0} then that many shared threads in the central pool may be used, otherwise all shared 
   * threads may be able to be used.
   * <p>
   * Different ranges may have minor different performance characteristics.  The most efficient 
   * returned pool would be where {@code maxThreads == 1} (and single threaded pool).  For 
   * multi-threaded pools the best option is where {@code maxThreads} is set less than or equal to
   * {@code guaranteedThreads + getGeneralProcessingThreadCount()}.
   * 
   * @param guaranteedThreads Number of threads the provided pool should be guaranteed to have
   * @param maxThreads Maximum number of threads to the returned pool can consume, or negative to use any available
   * @return A pool with the requested specifications for task scheduling or execution
   */
  public static SchedulerService rangedThreadCountPool(int guaranteedThreads, int maxThreads) {
    return rangedThreadCountPool(TaskPriority.High, guaranteedThreads, maxThreads);
  }
  
  /**
   * Requests a pool with a given range of threads.  Minimum threads are guaranteed to be available 
   * to tasks submitted to the returned threads.  In addition to those minimum threads tasks 
   * submitted may run on "general processing" threads (starts at {@code 1} but can be increased by 
   * invoking {@link #increaseGenericThreads(int)}).  If {@code maxThreads} is 
   * {@code > 0} then that many shared threads in the central pool may be used, otherwise all shared 
   * threads may be able to be used.
   * <p>
   * Different ranges may have minor different performance characteristics.  The most efficient 
   * returned pool would be where {@code maxThreads == 1} (and single threaded pool).  For 
   * multi-threaded pools the best option is where {@code maxThreads} is set less than or equal to
   * {@code guaranteedThreads + getGeneralProcessingThreadCount()}.
   * 
   * @param priority Priority for tasks submitted on returned scheduler service
   * @param guaranteedThreads Number of threads the provided pool should be guaranteed to have
   * @param maxThreads Maximum number of threads to the returned pool can consume, or negative to use any available
   * @return A pool with the requested specifications for task scheduling or execution
   */
  public static SchedulerService rangedThreadCountPool(TaskPriority priority, 
                                                       int guaranteedThreads, int maxThreads) {
    if (maxThreads == 1 && priority == TaskPriority.High) {
      // This single thread implementation is more memory intensive, but better performing
      return singleThreadPool(guaranteedThreads > 0);
    } else if (maxThreads > 0 && 
               Math.max(0, guaranteedThreads) + genericThreadCount >= maxThreads) {
      // specified max threads wont ever exceed general use count, so use more efficient scheduler
      return new MasterSchedulerResizingLimiter(priority, guaranteedThreads, maxThreads);
    } else {
      return new DynamicGenericThreadLimiter(priority, guaranteedThreads, maxThreads);
    }
  }
  
  /**
   * Returns the master scheduler with a default priority requested.
   * 
   * @param defaultPriority Default priority for tasks submitted to scheduler
   * @return Master scheduler with the provided default priority
   */
  private static PrioritySchedulerService masterScheduler(TaskPriority defaultPriority) {
    if (defaultPriority == TaskPriority.High) {
      return MASTER_SCHEDULER;
    } else if (defaultPriority ==  TaskPriority.Low) {
      return LOW_PRIORITY_MASTER_SCHEDULER;
    } else {
      return STARVABLE_PRIORITY_MASTER_SCHEDULER;
    }
  }
  
  /**
   * Implementation of {@link SingleThreadSchedulerSubPool} in order to get efficient single 
   * threaded execution on top of the central pool.  In addition to handling possible pool size 
   * changes, this also handles making sure the pool returns the same stats / values of the 
   * delegate pool.
   */
  protected static class SingleThreadSubPool extends SingleThreadSchedulerSubPool {
    @SuppressWarnings("unused")
    private final Object gcReference; // object just held on to track garbage collection
    
    protected SingleThreadSubPool(TaskPriority defaultPriority, TaskPriority tickPriority, 
                                  boolean threadGuaranteed) {
      super(masterScheduler(tickPriority), defaultPriority, LOW_PRIORITY_MAX_WAIT_IN_MS);

      this.gcReference = threadGuaranteed ? new PoolResizer(1) : null;
    }

    // SingleThreadSchedulerSubPool does not normally consider the parent pools load
    // but queued / task counts for this pool are different, the below functions ensure that behavior
    
    @Override
    public int getActiveTaskCount() {
      return MASTER_SCHEDULER.getActiveTaskCount();
    }
    
    @Override
    public int getQueuedTaskCount(TaskPriority priority) {
      return super.getQueuedTaskCount(priority) + MASTER_SCHEDULER.getQueuedTaskCount(priority);
    }
    
    @Override
    public int getWaitingForExecutionTaskCount(TaskPriority priority) {
      return super.getWaitingForExecutionTaskCount(priority) + 
               MASTER_SCHEDULER.getWaitingForExecutionTaskCount(priority);
    }
  }

  /**
   * This limiter is so that a scheduler wont use beyond it's guaranteed thread count (and the 
   * generic threads).  If used directly it is important to be sure the specified limit is set to 
   * be low enough that the pool wont consume beyond it's guaranteed threads + general use 
   * available at construction.
   * <p>
   * This is necessary to be sure that when a returned scheduler requests a given qty of threads, 
   * those resources are for sure available to them.
   */
  protected static class MasterSchedulerResizingLimiter extends SchedulerServiceLimiter {
    @SuppressWarnings("unused")
    private final Object gcReference; // object just held on to track garbage collection
    
    public MasterSchedulerResizingLimiter(TaskPriority priority, 
                                          int guaranteedThreads, int maxThreads) {
      super(masterScheduler(priority), maxThreads < 1 ? Integer.MAX_VALUE : maxThreads);
      
      if (maxThreads > 0 && guaranteedThreads > maxThreads) {
        throw new IllegalArgumentException("Max threads must be <= guaranteed threads");
      }
      
      this.gcReference = guaranteedThreads > 0 ? new PoolResizer(guaranteedThreads) : null;
    }
  }
  
  /**
   * Similar to the extended classes {@link MasterSchedulerResizingLimiter} this class is for 
   * ensuring that no single scheduler can completely dominate the central pool.  This class 
   * however is for when limits are set very high, and we may need to be able to adapt to added 
   * generic threads in the future.  This does have minor performance implications so if you don't 
   * need to be flexible for future general use threads the {@link MasterSchedulerResizingLimiter} 
   * is a better option.
   */
  protected static class DynamicGenericThreadLimiter extends MasterSchedulerResizingLimiter {
    private final int guaranteedThreads;
    private final int maxThreads;
    
    public DynamicGenericThreadLimiter(TaskPriority priority, int guaranteedThreads, int maxThreads) {
      super(priority, guaranteedThreads, maxThreads);
      
      this.guaranteedThreads = guaranteedThreads > 0 ? guaranteedThreads : 0;
      this.maxThreads = getMaxConcurrency();
    }

    @Override
    protected boolean canSubmitTasksToPool() {
      int allowedConcurrency = Math.min(maxThreads, guaranteedThreads + genericThreadCount);
      if (allowedConcurrency != getMaxConcurrency()) {
        setMaxConcurrency(allowedConcurrency);
      }
      
      return super.canSubmitTasksToPool();
    }
  }
  
  /**
   * Class which adjusts the size of the master pool up once it is constructed.  And down once it 
   * is garbage collected (as an assumption that the class which needed the resize is no longer 
   * available).
   * <p>
   * While using the garbage collector is not normally ideal for something like this, it avoids the 
   * need to have a shutdown action on returned pools.  In addition a delay in reducing a pool size 
   * down is desirable to reduce potential thread churn of the central pool.
   */
  protected static class PoolResizer {
    private final int amount;
    
    public PoolResizer(int amount) {
      this.amount = amount;

      POOL_SIZE_UPDATER.adjustPoolSize(amount);
    }
    
    @Override
    protected void finalize() {
      POOL_SIZE_UPDATER.adjustPoolSize(-amount);
    }
  }
  
  /**
   * Class for handling the mechanics for adjusting the master schedulers pool size.  This class's 
   * primary job is sending updates to that scheduler so that the applications needs are met, but 
   * churn is minimized.
   */
  protected static class PoolResizeUpdater extends ReschedulingOperation {
    protected static final int POOL_SIZE_UPDATE_DELAY = 120_000; // delayed pool size changes reduce churn
    
    protected LongAdder poolSizeChange;

    protected PoolResizeUpdater(SubmitterScheduler scheduler) {
      super(scheduler, POOL_SIZE_UPDATE_DELAY);
      
      poolSizeChange = new LongAdder();
    }
    
    public void adjustPoolSize(int delta) {
      if (delta > 0) {
        // increases we handle immediately, threads will still be lazily started
        MASTER_SCHEDULER.adjustPoolSize(delta);
      } else {
        // decreases are delayed since thread stops may be immediate
        poolSizeChange.add(delta);
        signalToRun();
      }
    }

    @Override
    protected void run() {
      int adjustment = poolSizeChange.intValue();
      if (adjustment != 0) {
        poolSizeChange.add(-adjustment);
        MASTER_SCHEDULER.adjustPoolSize(adjustment);
      }
    }
  }
}
