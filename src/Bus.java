import java.util.*;

public class Bus {
  private static final int WORD_LATENCY_CACHE = 2;
  private static final int BLOCK_LATENCY_MEM = 100;
  private static final int WORD_SIZE = 4;

  enum BusState {
    READY, BUSY
  }

  private List<Cache> caches;
  private BusState busState;
  private int busyCycles;
  private int exitCycles;

  // State variables
  private List<Cache> requesterQueue;
  private Cache requester;
  private Cache responder;
  private BusTransaction result;

  public Bus() {
    caches = new ArrayList<>();
    busState = BusState.READY;
    busyCycles = 0;
    exitCycles = 0;
    requesterQueue = new ArrayList<>();
  }

  public void registerCache(Cache cache) {
    caches.add(cache);
  }

  public void reserve(Cache cache) {
    if (!requesterQueue.contains(cache)) {
      requesterQueue.add(cache);
    }
  }

  public void tick() {
    switch (busState) {
      case BUSY:
        busyCycles--;
        exitCycles--;
        if (exitCycles == 0) {
          requester.exitBus(result);
          requester = null;
          result = null;
        }
        if (busyCycles == 0) {
          if (responder != null) {
            responder.unhog();
          }
          responder = null;
          busState = BusState.READY;
        }
        break;
      case READY:
        if (requesterQueue.isEmpty()) {
          return;
        }
        requester = requesterQueue.remove(0);
        BusTransaction transaction = requester.accessBus();
        switch (transaction.getTransition()) {
          case BUS_RD:
            busRd(transaction);
            break;
          case BUS_RD_X:
            busRdX(transaction);
            break;
          case FLUSH:
            flush(transaction);
            break;
          case BUS_UPD:
            // TODO
            break;
          case FLUSH_OPT:
            break;
        }
    }
  }

  private void busRd(BusTransaction transaction) {
    // Propagate to caches
    boolean foundResponder = false;
    for (Cache cache : caches) {
      Optional<BusTransaction> response = cache.snoop(transaction);
      // Fetch from first cache that responds with FlushOpt
      if (!foundResponder && response.isPresent()) {
        foundResponder = true;
        responder = cache;
        responder.hog();
        result = transaction;
        result.setShared(true);
        exitCycles = WORD_LATENCY_CACHE * (transaction.getSize() / WORD_SIZE);
        busyCycles = response.get().getTransition() == Transition.FLUSH_OPT ? exitCycles : BLOCK_LATENCY_MEM;
        busState = BusState.BUSY;
      }
    }

    // Fetch from memory if no cache responded
    if (!foundResponder) {
      result = transaction;
      result.setShared(false);
      exitCycles = BLOCK_LATENCY_MEM;
      busyCycles = exitCycles;
      busState = BusState.BUSY;
    }
  }

  private void busRdX(BusTransaction transaction) {
    busRd(transaction);
  }

  private void flush(BusTransaction transaction) {
    result = transaction;
    exitCycles = BLOCK_LATENCY_MEM;
    busyCycles = exitCycles;
    busState = BusState.BUSY;
  }
}
