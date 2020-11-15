import java.util.*;
import java.util.stream.Collectors;

public class Bus {
  private static final int INVALIDATE_LATENCY_CACHE = 1;
  private static final int WORD_LATENCY_CACHE = 2;
  private static final int BLOCK_LATENCY_MEM = 100;
  private static final int WORD_SIZE = 4;

  enum BusState {
    READY, BUSY
  }

  private List<Cache> caches;

  // State variables
  private BusState busState;
  private int primaryCycles;
  private int secondaryCycles;
  private List<Cache> requesterQueue;
  private Cache requester;
  private Set<Cache> hogged = Set.of();
  private BusTransaction result;

  // Statistics
  private long busTrafficBytes = 0;
  private long busNumInvalidations = 0;
  private long busNumUpdates = 0;

  public Bus() {
    caches = new ArrayList<>();
    busState = BusState.READY;
    primaryCycles = 0;
    secondaryCycles = 0;
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
            busNumInvalidations++;
            break;
          case BUS_UPGR:
            busUpgr(transaction);
            busNumInvalidations++;
            break;
          case FLUSH:
            flush(transaction);
            break;
          case BUS_UPD:
            busUpd(transaction);
            busNumUpdates++;
            break;
          case FLUSH_OPT:
            throw new RuntimeException("Some cache directly requested FlushOpt from the bus");
        }
        busTrafficBytes += result.getSize();
        busState = BusState.BUSY;
    }
  }

  public void tock() {
    switch (busState) {
      case BUSY:
        primaryCycles = Integer.max(primaryCycles - 1, -1);
        secondaryCycles = Integer.max(secondaryCycles - 1, -1);
        if (primaryCycles == 0) {
          requester.exitBus(result);
          requester = null;
          result = null;
        }
        if (secondaryCycles == 0) {
          hogged.stream().forEach(c -> c.unhog());
          hogged = Set.of();
        }
        if (primaryCycles <= 0 && secondaryCycles <= 0) {
          busState = BusState.READY;
        }
        break;
      case READY:
        break;
    }
  }

  public Map<String, Number> getBusStatistics() {
    Map<String, Number> statistics = new LinkedHashMap<>();
    statistics.put("Bus Traffic (bytes)", busTrafficBytes);
    statistics.put("Invalidations", busNumInvalidations);
    statistics.put("Updates", busNumUpdates);
    return statistics;
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
        primaryCycles = WORD_LATENCY_CACHE * (transaction.getSize() / WORD_SIZE);
        if (response.get().getTransition() == Transition.FLUSH_OPT) {
          secondaryCycles = primaryCycles;
        } else {
          secondaryCycles = Integer.max(BLOCK_LATENCY_MEM, primaryCycles);
        }
      }
    }

    // Fetch from memory if no cache responded
    if (!foundResponder) {
      primaryCycles = BLOCK_LATENCY_MEM;
    }
  }

  private void busRdX(BusTransaction transaction) {
    busRd(transaction);
  }

  private void busUpgr(BusTransaction transaction) {
    for (Cache cache : caches) {
      if (cache == requester) {
        continue;
      }
      cache.snoop(transaction);
    }
    result = transaction;
    primaryCycles = INVALIDATE_LATENCY_CACHE;
  }

  private void flush(BusTransaction transaction) {
    result = transaction;
    primaryCycles = BLOCK_LATENCY_MEM;
  }

  private void busUpd(BusTransaction transaction) {
    result = transaction;

    Set<Cache> containsAddr = caches.stream().filter(c -> c != requester && c.contains(transaction.getAddress()))
        .collect(Collectors.toSet());
    boolean shared = !containsAddr.isEmpty();
    result.setShared(shared);

    if (shared) {
      hogged = containsAddr;
      hogged.stream().forEach(c -> {
        c.snoop(transaction);
        c.hog();
      });
      primaryCycles = WORD_LATENCY_CACHE * (transaction.getSize() / WORD_SIZE);
      secondaryCycles = primaryCycles;
    } else {
      primaryCycles = INVALIDATE_LATENCY_CACHE;
      result.setSize(0);
    }
  }
}
