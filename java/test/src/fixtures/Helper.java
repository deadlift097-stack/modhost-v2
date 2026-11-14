package fixtures;

/** A public class with a constructor but no entry-point method. It must not be picked as the entry. */
public class Helper {
    public int twice(int x) {
        return x * 2;
    }
}
