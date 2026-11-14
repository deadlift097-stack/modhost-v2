package fixtures;

public class MainOnly {
    public static volatile boolean ran;

    public static void main(String[] args) {
        ran = args != null;
    }
}
