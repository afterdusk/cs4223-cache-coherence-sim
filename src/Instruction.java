public class Instruction {
  enum Type {
    LOAD, STORE, OTHER
  }

  public Type type;
  public int address;

  public Instruction(Type type, int address) {
    this.type = type;
    this.address = address;
  }
}
