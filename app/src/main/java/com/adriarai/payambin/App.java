package com.adriarai.payambin;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Crash.install(this);
    }
}
