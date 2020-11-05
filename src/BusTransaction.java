public class BusTransaction {
  private Transition transition;
  private int address;
  private int size;

  public BusTransaction(Transition transition, int address, int size) {
    this.transition = transition;
    this.address = address;
    this.size = size;
  }

  public BusTransaction(Transition transition, int address) {
    this(transition, address, 0);
  }

  public Transition getTransition() {
    return transition;
  }

  public int getAddress() {
    return address;
  }

  public int getSize() {
    return size;
  }
}