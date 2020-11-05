public class Instruction {
  enum Type {
    LOAD, STORE, OTHER
  }

  private Type type;
  /**
   * Represents an address if type is LOAD/STORE, cycle duration otherwise
   */
  private int value;

  public Instruction(Type type, int value) {
    this.type = type;
    this.value = value;
  }

  public Type getType() {
    return type;
  }

  public int getAddress() {
    if (type == Type.OTHER) {
      throw new RuntimeException("Cannot call getAddress() on instruction type OTHER");
    }
    return value;
  }

  public int getCycleDuration() {
    if (type == Type.LOAD || type == Type.STORE) {
      throw new RuntimeException("Cannot call getCycleDuration() on instruction type LOAD or STORE");
    }
    return value;
  }
}
