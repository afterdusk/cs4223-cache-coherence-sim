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
  private Cache pendingAccess;

  public Bus() {
    caches = new ArrayList<>();
  }

  public void registerCache(Cache cache) {
    caches.add(cache);
  }

  public void reserve(Cache cache) {
    if (pendingAccess == null) {
      pendingAccess = cache;
    }
  }

  public void tick() {
    switch (busState) {
      case BUSY:
        busyCycles--;
        if (busyCycles == 0) {
          busState = BusState.READY;
        }
        break;
      case READY:
        if (pendingAccess == null) {
          break;
        }
        BusTransaction transaction = pendingAccess.accessBus();
        switch (transaction.getTransition()) {
          case BUS_RD:
            busRd(transaction);
            break;
          case BUS_RD_X:
          case FLUSH:
          case FLUSH_OPT:
            break;
        }
    }
  }

  private void busRd(BusTransaction transaction) {
    boolean inCaches = false;
    for (Cache cache : caches) {
      if (cache.contains(transaction.getAddress())) {
        inCaches = true;
        break;
      }
    }
    if (inCaches) {
      // Fetch from cache
      busyCycles = WORD_LATENCY_CACHE * transaction.getSize();
    } else {
      // Fetch from memory
      busyCycles = BLOCK_LATENCY_MEM;
    }
    busState = BusState.BUSY;
  }
}
