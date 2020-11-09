public class BusTransaction {
  private Transition transition;
  private int address;
  private int size;

  private boolean shared;

  public BusTransaction(Transition transition, int address, int size) {
    this.transition = transition;
    this.address = address;
    this.size = size;
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

  public void setShared(boolean shared) {
    this.shared = shared;
  }

  public boolean getShared() {
    return shared;
  }
}