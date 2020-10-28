import java.util.*;

public class Bus {
  List<Cache> caches;

  public Bus(List<Cache> caches) {
    this.caches = caches;
    for (Cache cache : caches) {
      cache.bus = this;
    }
  }

  public void tick() {
    for (Cache cache : caches) {
      cache.snoop();
    }
  }
}
