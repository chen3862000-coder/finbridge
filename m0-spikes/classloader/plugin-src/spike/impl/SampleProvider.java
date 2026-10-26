package spike.impl;

import spike.api.Provider;
import spike.privateapi.Helper;

public final class SampleProvider implements Provider {
    @Override
    public String call() {
        return Helper.marker();
    }

    @Override
    public Class<?> helperClass() {
        return Helper.class;
    }
}
