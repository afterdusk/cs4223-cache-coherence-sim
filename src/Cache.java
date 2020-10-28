public class Cache {
  public Processor processor;
  public Bus bus;

  public Cache(Processor processor, Simulator.Protocol protocol, int cacheSize, int associativity, int blockSize) {
    this.processor = processor;
  }

  public void issue() {
    // TODO: Update stats
  }

  public void snoop() {
    processor.unstall();
  }
}
