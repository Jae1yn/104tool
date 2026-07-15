package tool104.app;

/** fat jar 入口：不继承 Application，绕过 JavaFX 对主类的运行时组件检查。 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Main.main(args);
    }
}
