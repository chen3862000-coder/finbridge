package spike.api;

public interface Provider {
    String call();

    Class<?> helperClass();
}
