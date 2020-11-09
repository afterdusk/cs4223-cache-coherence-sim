import java.util.*;
import java.util.stream.Collectors;

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
  private Set<Cache> hogged = Set.of();
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

  public void reserveToFront(Cache cache) {
    if (requesterQueue.contains(cache) || !(cache instanceof DragonCache))
      throw new RuntimeException("Reserve to front should only be called by Dragon after BusRd for PrWrMiss");

    requesterQueue.add(0, cache);
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
          hogged.stream().forEach(c -> c.unhog());
          hogged = Set.of();
        }
        busyCycles = Integer.max(busyCycles, -1);
        exitCycles = Integer.max(exitCycles, -1);
        if (busyCycles <= 0 && exitCycles <= 0) {
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
            busUpd(transaction);
            break;
          case FLUSH_OPT:
            throw new RuntimeException("Some cache directly requested FlushOpt from the bus");
        }
    }
  }

  private void busRd(BusTransaction transaction) {
    result = transaction;

    boolean shared = caches.stream().filter(c -> c != requester).anyMatch(c -> c.contains(transaction.getAddress()));
    result.setShared(shared);

    // Propagate to caches
    boolean foundResponder = false;
    for (Cache cache : caches) {
      if (cache == requester)
        continue;

      Optional<BusTransaction> response = cache.snoop(transaction);
      // Fetch from first cache that responds with FlushOpt
      if (!foundResponder && response.isPresent()) {
        foundResponder = true;
        hogged = Set.of(cache);
        cache.hog();
        exitCycles = WORD_LATENCY_CACHE * (transaction.getSize() / WORD_SIZE);
        busyCycles = response.get().getTransition() == Transition.FLUSH_OPT ? exitCycles : BLOCK_LATENCY_MEM;
        exitCycles--;
        busyCycles--;
      }
    }

    // Fetch from memory if no cache responded
    if (!foundResponder) {
      exitCycles = BLOCK_LATENCY_MEM - 1;
    }

    busState = BusState.BUSY;
  }

  private void busRdX(BusTransaction transaction) {
    busRd(transaction);
  }

  private void flush(BusTransaction transaction) {
    result = transaction;
    exitCycles = BLOCK_LATENCY_MEM - 1;
    busState = BusState.BUSY;
  }

  private void busUpd(BusTransaction transaction) {
    result = transaction;

    Set<Cache> containsAddr = caches.stream().filter(c -> c != requester && c.contains(transaction.getAddress()))
        .collect(Collectors.toSet());
    boolean shared = !containsAddr.isEmpty();
    result.setShared(shared);

    if (shared) {
      hogged = containsAddr;
      hogged.stream().forEach(c -> c.hog());
      exitCycles = WORD_LATENCY_CACHE * (transaction.getSize() / WORD_SIZE) - 1;
      busyCycles = exitCycles;
      busState = BusState.BUSY;
    } else {
      requester.exitBus(result);
    }
  }
}
